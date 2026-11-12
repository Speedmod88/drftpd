package org.drftpd.zipscript.master.nfo;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bushe.swing.event.annotation.AnnotationProcessor;
import org.bushe.swing.event.annotation.EventSubscriber;
import org.drftpd.common.extensibility.PluginInterface;
import org.drftpd.common.network.AsyncCommandArgument;
import org.drftpd.common.util.ConfigLoader;
import org.drftpd.master.event.MessageEvent;
import org.drftpd.master.event.ReloadEvent;
import org.drftpd.master.event.TransferEvent;
import org.drftpd.master.GlobalContext;
import org.drftpd.master.indexation.AdvancedSearchParams;
import org.drftpd.master.slavemanagement.RemoteSlave;
import org.drftpd.master.vfs.FileHandle;
import org.drftpd.master.vfs.event.VirtualFileSystemInodeDeletedEvent;
import org.drftpd.master.vfs.event.VirtualFileSystemInodeRefreshEvent;
import org.drftpd.master.vfs.event.VirtualFileSystemRenameEvent;
import org.drftpd.slave.NfoReader;
import org.drftpd.slave.network.AsyncResponseLog;
import org.drftpd.slave.network.AsyncResponseMaxPath;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.file.Path;

/** Bounded CWD wait; all database and slave I/O runs on one private worker. */
public final class NfoDisplay implements PluginInterface {
    private static final Logger logger = LogManager.getLogger(NfoDisplay.class);
    private static volatile NfoDisplay active;
    private static final String TIMER = "nfo-display.maintenance";
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), task -> {
                Thread thread = new Thread(task, "NFO Display Cache");
                thread.setDaemon(true); thread.setPriority(Thread.MIN_PRIORITY); return thread;
            }, new ThreadPoolExecutor.AbortPolicy()) {
        @Override protected void terminated() { closeStore(); }
    };
    private final Map<Key, Entry> cache = new LinkedHashMap<>(16, .75f, true);
    private final Map<Key, CompletableFuture<List<String>>> pending = new HashMap<>();
    private final Set<String> invalidations = new LinkedHashSet<>();
    private final AtomicBoolean maintenanceQueued = new AtomicBoolean();
    private final Map<String, Capability> capabilities = new LinkedHashMap<>();
    private volatile Settings settings = new Settings(false, 200, 256, 3600000);
    private volatile boolean closed;
    private volatile Storage storage = new Storage(false, Path.of("userdata/nfo-display"), 500, false, 8, 2000);
    private boolean clearStore, timerRegistered;
    private long revision;
    private NfoStore store;
    private Path storePath;
    private long retryStoreAt, lastCommit, nextPrefetch, nextPass, nextPrune;

    static record Storage(boolean enabled, Path path, int waitMillis, boolean prefetch, int batch, long interval) {
        static Storage read(Properties p) {
            String path = p.getProperty("store.path", "userdata/nfo-display").trim();
            if (path.isEmpty()) throw new IllegalArgumentException("store.path must not be empty");
            return new Storage(Boolean.parseBoolean(p.getProperty("store.enabled", "true")),
                    Path.of(path).toAbsolutePath().normalize(),
                    Settings.number(p, "cwd.wait.millis", 500, 0, 2000),
                    Boolean.parseBoolean(p.getProperty("prefetch.enabled", "true")),
                    Settings.number(p, "prefetch.batch", 8, 1, 32),
                    Settings.number(p, "prefetch.interval.seconds", 2, 1, 3600) * 1000L);
        }
    }

    static record Settings(boolean enabled, int maxLines, int entries, long ttlMillis) {
        static Settings read(Properties p) {
            return new Settings(Boolean.parseBoolean(p.getProperty("enabled", "true")),
                    number(p, "max.lines", 200, 1, 1000), number(p, "cache.entries", 256, 1, 1024),
                    number(p, "cache.minutes", 60, 1, 1440) * 60000L);
        }
        private static int number(Properties p, String key, int fallback, int min, int max) {
            int value = Integer.parseInt(p.getProperty(key, Integer.toString(fallback)));
            if (value < min || value > max) throw new IllegalArgumentException(key + " must be " + min + ".." + max);
            return value;
        }
    }
    static record Key(String path, long created, long size, long modified, long crc) {
        static Key of(FileHandle file) throws Exception {
            return new Key(file.getPath(), file.creationTime(), file.getSize(), file.lastModified(), file.getCheckSumCached());
        }
    }
    private record Entry(List<String> lines, long expires) { }
    private record Capability(long generation, boolean supported) { }

    public static NfoDisplay current() { return active; }
    public boolean enabled() { return settings.enabled && !closed; }

    @Override public void startPlugin() {
        load();
        active = this;
        AnnotationProcessor.process(this);
        GlobalContext.getGlobalContext().scheduleTimer(TIMER, getClass().getName(), this::dispatch, 1000, 1000);
        timerRegistered = true;
    }
    private void load() {
        try {
            Properties config = ConfigLoader.loadPluginConfig("nfo-display.conf");
            Settings updated = Settings.read(config);
            Storage updatedStorage = Storage.read(config);
            settings = updated;
            storage = updatedStorage;
        }
        catch (RuntimeException e) {
            settings = new Settings(false, 200, 256, 3600000);
            logger.warn("NFO display disabled: cannot load nfo-display.conf", e);
        }
        synchronized (this) {
            revision++;
            cache.clear();
            pending.values().forEach(future -> future.complete(List.of()));
            pending.clear();
        }
    }
    @EventSubscriber public void reload(ReloadEvent event) { load(); }
    @EventSubscriber public void uploaded(TransferEvent event) {
        if (!enabled() || !"STOR".equals(event.getCommand())) return;
        try { lines(event.getTransferFile()); }
        catch (Exception e) { logger.debug("NFO prefetch skipped: {}", e.toString()); }
    }
    @EventSubscriber(eventServiceName = GlobalContext.SERVICE_NAME_EVENT_BUS_SLOWEST)
    public void deleted(VirtualFileSystemInodeDeletedEvent event) {
        var inode = event.getImmutableInode();
        if (inode.isDirectory() || isNfo(inode.getPath())) invalidate(inode.getPath());
    }
    @EventSubscriber public void renamed(VirtualFileSystemRenameEvent event) {
        if (event.getImmutableInode().isDirectory() || isNfo(event.getSource().getPath()) || isNfo(event.getImmutableInode().getPath())) {
            invalidate(event.getSource().getPath());
            invalidate(event.getImmutableInode().getPath());
        }
    }
    @EventSubscriber public void refreshed(VirtualFileSystemInodeRefreshEvent event) {
        if (isNfo(event.getImmutableInode().getPath())) invalidate(event.getImmutableInode().getPath());
    }

    private static boolean isNfo(String path) {
        return path.length() >= 4 && path.regionMatches(true, path.length() - 4, ".nfo", 0, 4);
    }
    private static boolean within(String path, String parent) {
        return parent.equals("/") || path.equals(parent)
                || (path.length() > parent.length() && path.startsWith(parent) && path.charAt(parent.length()) == '/');
    }

    synchronized void invalidate(String path) {
        if (closed) return;
        revision++;
        cache.keySet().removeIf(key -> within(key.path, path));
        pending.forEach((key, future) -> { if (within(key.path, path)) future.complete(List.of()); });
        if (clearStore || invalidations.stream().anyMatch(parent -> within(path, parent))) return;
        invalidations.removeIf(child -> within(child, path));
        if (path.equals("/") || invalidations.size() >= 256) {
            clearStore = true; invalidations.clear(); cache.clear();
        } else invalidations.add(path);
    }
    @EventSubscriber public void shutdown(MessageEvent event) {
        if ("SHUTDOWN".equals(event.getCommand())) stopPlugin("shutdown");
    }
    @Override public void stopPlugin(String reason) {
        closed = true;
        if (active == this) active = null;
        AnnotationProcessor.unprocess(this);
        if (timerRegistered) GlobalContext.getGlobalContext().cancelTimer(TIMER);
        synchronized (this) {
            cache.clear();
            pending.values().forEach(future -> future.complete(List.of()));
            pending.clear();
        }
        worker.shutdown();
        try {
            if (!worker.awaitTermination(12, TimeUnit.SECONDS)) logger.warn("NFO worker is still finishing; store will close when it exits");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    List<String> lines(FileHandle file) throws Exception {
        return request(file).getNow(List.of());
    }

    List<String> linesForCwd(FileHandle file) throws Exception {
        Key key = Key.of(file);
        var result = request(file);
        try {
            List<String> text = storage.waitMillis == 0 ? result.getNow(List.of())
                    : result.get(storage.waitMillis, TimeUnit.MILLISECONDS);
            return eligible(file) && key.equals(Key.of(file)) ? text : List.of();
        } catch (TimeoutException e) { return List.of(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return List.of(); }
    }

    private boolean eligible(FileHandle file) throws Exception {
        return enabled() && isNfo(file.getName()) && !file.isLink()
                && !file.isTransferring() && file.getXfertime() != -1 && file.getSize() > 0 && file.getSize() <= NfoReader.MAX_BYTES;
    }

    private CompletableFuture<List<String>> request(FileHandle file) throws Exception {
        if (!eligible(file)) return CompletableFuture.completedFuture(List.of());
        Key key = Key.of(file);
        CompletableFuture<List<String>> future;
        long requestedRevision;
        synchronized (this) {
            Entry entry = cache.get(key);
            if (entry != null && entry.expires > System.currentTimeMillis()) return CompletableFuture.completedFuture(entry.lines);
            if (pending.containsKey(key)) return pending.get(key);
            if (pending.size() >= 32) return CompletableFuture.completedFuture(List.of());
            future = new CompletableFuture<>();
            pending.put(key, future);
            requestedRevision = revision;
        }
        Settings requested = settings;
        try { worker.execute(() -> fetch(file, key, requested, requestedRevision, future, false)); }
        catch (RejectedExecutionException e) {
            synchronized (this) { pending.remove(key, future); }
            future.complete(List.of());
        }
        return future;
    }

    private void fetch(FileHandle file, Key key, Settings requested, long requestedRevision,
                       CompletableFuture<List<String>> future, boolean idleOnly) {
        List<String> text = List.of();
        boolean success = false, attempted = false;
        try {
            openStore();
            drainInvalidations();
            if (!eligible(file) || settings != requested || !key.equals(Key.of(file))) return;
            if (store != null) {
                try {
                    String saved = store.get(key);
                    if (saved != null) { text = safeLines(saved, requested.maxLines); success = true; return; }
                } catch (Exception e) {
                    logger.warn("Unreadable stored NFO discarded: path={}", key.path, e);
                    try { store.removeTree(key.path); }
                    catch (Exception removalError) { storeFailed(removalError); }
                }
            }
            List<RemoteSlave> copies = new ArrayList<>(file.getSlaves());
            copies.sort(Comparator.comparing(RemoteSlave::getName));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            for (RemoteSlave slave : copies) {
                if (closed || System.nanoTime() >= deadline) break;
                if (!slave.isAvailable() || slave.isRemerging() || !slave.getRenameQueue().isEmpty()) continue;
                if (idleOnly && slave.getSlaveStatus().getTransfers() != 0) continue;
                attempted = true;
                long generation = slave.getConnectionGeneration();
                Capability capability = capabilities.get(slave.getName());
                if (capability == null || capability.generation != generation) {
                    String probe = slave.fetchIndex();
                    slave.sendCommand(new AsyncCommandArgument(probe, "maxpath", NfoReader.PROBE));
                    boolean supported = ((AsyncResponseMaxPath) slave.fetchResponseWithoutDisconnect(probe, responseBudget(deadline))).getMaxPath()
                            == NfoReader.CAPABILITY;
                    capability = new Capability(generation, supported);
                    capabilities.put(slave.getName(), capability);
                    if (capabilities.size() > 256) capabilities.remove(capabilities.keySet().iterator().next());
                }
                if (!capability.supported || slave.getConnectionGeneration() != generation) continue;
                String index = slave.fetchIndex();
                slave.sendCommand(new AsyncCommandArgument(index, "nforead", new String[]{key.path, Long.toString(key.size)}));
                AsyncResponseLog response = (AsyncResponseLog) slave.fetchResponseWithoutDisconnect(index, responseBudget(deadline));
                if (response.getError() != null || response.getContent() == null) continue;
                if (!eligible(file) || settings != requested || slave.getConnectionGeneration() != generation
                        || !key.equals(Key.of(file))) return;
                synchronized (this) { if (revision != requestedRevision) return; }
                String content = response.getContent();
                text = safeLines(content, requested.maxLines);
                if (text.isEmpty()) return;
                if (store != null) {
                    try { store.put(key, content); }
                    catch (Exception e) { storeFailed(e); }
                }
                success = true;
                break;
            }
        } catch (Exception e) { logger.debug("NFO background read skipped: path={} reason={}", key.path, e.toString()); }
        finally {
            synchronized (this) {
                pending.remove(key, future);
                if (!closed && requested == settings && revision == requestedRevision) {
                    if (success || attempted) {
                        cache.put(key, new Entry(text, System.currentTimeMillis() + (success ? requested.ttlMillis : 60000)));
                        while (cache.size() > requested.entries) cache.remove(cache.keySet().iterator().next());
                    }
                    future.complete(text);
                } else future.complete(List.of());
            }
        }
    }

    private static int responseBudget(long deadline) throws java.io.IOException {
        long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (remaining <= 0) throw new java.io.IOException("NFO background read deadline exceeded");
        return (int) Math.min(5000, remaining);
    }

    private void openStore() {
        Storage config = storage;
        if (store != null && (!config.enabled || !config.path.equals(storePath))) closeStore();
        if (store != null || !config.enabled || System.currentTimeMillis() < retryStoreAt) return;
        try {
            store = new NfoStore(config.path);
            storePath = config.path;
            retryStoreAt = 0;
            logger.info("Persistent NFO store opened: path={} prefetch={} cwdWaitMs={}", config.path, config.prefetch, config.waitMillis);
        } catch (Exception e) {
            retryStoreAt = System.currentTimeMillis() + 60000;
            logger.warn("Persistent NFO store unavailable; using memory cache: {}", config.path, e);
        }
    }

    private void storeFailed(Exception error) {
        logger.warn("NFO persistence failed; memory caching remains available, database retry in 60 seconds", error);
        closeStore();
        retryStoreAt = System.currentTimeMillis() + 60000;
    }

    private void drainInvalidations() throws Exception {
        if (store == null) return;
        List<String> paths;
        boolean all;
        synchronized (this) {
            all = clearStore;
            paths = List.copyOf(invalidations);
            clearStore = false;
            invalidations.clear();
        }
        try {
            if (all) store.removeTree("/");
            else for (String path : paths) store.removeTree(path);
        } catch (Exception error) {
            synchronized (this) {
                if (all || invalidations.size() + paths.size() > 256) {
                    clearStore = true; invalidations.clear(); cache.clear();
                } else invalidations.addAll(paths);
            }
            throw error;
        }
    }

    private void closeStore() {
        if (store == null) return;
        try { drainInvalidations(); }
        catch (Exception e) { logger.warn("NFO invalidation flush failed", e); }
        try { store.close(); }
        catch (Exception e) { logger.warn("Persistent NFO store close failed", e); }
        finally { store = null; storePath = null; }
    }

    private void dispatch() {
        if (closed || !worker.getQueue().isEmpty() || !maintenanceQueued.compareAndSet(false, true)) return;
        try {
            worker.execute(() -> {
                try { maintain(); }
                catch (Exception e) { logger.debug("NFO maintenance deferred", e); }
                finally { maintenanceQueued.set(false); }
            });
        } catch (RejectedExecutionException e) { maintenanceQueued.set(false); }
    }

    private void maintain() throws Exception {
        openStore();
        drainInvalidations();
        if (store == null || closed) return;
        long now = System.currentTimeMillis();
        if (now - lastCommit >= 30000) { store.commit(); lastCommit = now; }
        if (now >= nextPrune) {
            String after = store.cursor("prune");
            List<Key> rows = store.page(after, 32);
            for (Key key : rows) {
                try {
                    FileHandle file = new FileHandle(key.path);
                    if (!key.equals(Key.of(file))) store.removeTree(key.path);
                } catch (java.io.FileNotFoundException e) { store.removeTree(key.path); }
                after = key.path;
            }
            store.cursor("prune", rows.isEmpty() ? "" : after);
            nextPrune = now + (rows.isEmpty() ? 600000 : 1000);
        }
        Storage config = storage;
        if (!enabled() || !config.prefetch || now < nextPrefetch || now < nextPass
                || GlobalContext.getEventServiceSlowest().getQueueSize() >= 5000) return;
        nextPrefetch = now + config.interval;
        AdvancedSearchParams params = new AdvancedSearchParams();
        params.setAfterPath(store.cursor("prefetch")); params.setLimit(config.batch);
        params.setInodeType(AdvancedSearchParams.InodeType.FILE); params.setEndsWith(".nfo");
        var context = GlobalContext.getGlobalContext();
        var paths = context.getIndexEngine().advancedFind(context.getRoot(), params, "NFO-prefetch");
        String last = null;
        for (String path : paths.keySet()) {
            if (closed || !worker.getQueue().isEmpty()) break;
            last = path;
            try {
                FileHandle file = new FileHandle(path);
                if (!eligible(file)) continue;
                Key key = Key.of(file);
                long currentRevision;
                synchronized (this) {
                    if (cache.containsKey(key)) continue;
                    currentRevision = revision;
                }
                fetch(file, key, settings, currentRevision, new CompletableFuture<>(), true);
            } catch (Exception e) {
                logger.debug("NFO prefetch candidate unavailable: path={} reason={}", path, e.toString());
            }
        }
        if (store != null) {
            if (paths.isEmpty()) { store.cursor("prefetch", ""); nextPass = now + 3600000; }
            else if (last != null) store.cursor("prefetch", last);
        }
    }

    static List<String> safeLines(String text, int limit) {
        if (text.length() > NfoReader.MAX_BYTES) return List.of();
        String cleaned = text.replaceAll("\\x1B\\[[0-?]*[ -/]*[@-~]", "")
                .replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "");
        List<String> lines = new ArrayList<>();
        for (String line : cleaned.split("\\r\\n|\\r|\\n", -1)) {
            if (lines.size() == limit) { lines.add("[NFO display truncated]"); break; }
            lines.add(line.length() > 512 ? line.substring(0, 512) : line);
        }
        return List.copyOf(lines);
    }
}
