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
import org.drftpd.master.slavemanagement.RemoteSlave;
import org.drftpd.master.vfs.FileHandle;
import org.drftpd.slave.NfoReader;
import org.drftpd.slave.network.AsyncResponseLog;
import org.drftpd.slave.network.AsyncResponseMaxPath;
import java.util.*;
import java.util.concurrent.*;

/** CWD reads only this bounded cache; all slave requests run on a private worker. */
public final class NfoDisplay implements PluginInterface {
    private static final Logger logger = LogManager.getLogger(NfoDisplay.class);
    private static volatile NfoDisplay active;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), task -> {
                Thread thread = new Thread(task, "NFO Display Cache");
                thread.setDaemon(true); thread.setPriority(Thread.MIN_PRIORITY); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private final Map<Key, Entry> cache = new LinkedHashMap<>(16, .75f, true);
    private final Set<Key> pending = new HashSet<>();
    private final Map<String, Capability> capabilities = new LinkedHashMap<>();
    private volatile Settings settings = new Settings(false, 200, 256, 3600000);
    private volatile boolean closed;

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
    private record Key(String path, long created, long size, long modified, long crc) {
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
    }
    private void load() {
        try { settings = Settings.read(ConfigLoader.loadPluginConfig("nfo-display.conf")); }
        catch (RuntimeException e) {
            settings = new Settings(false, 200, 256, 3600000);
            logger.warn("NFO display disabled: cannot load nfo-display.conf", e);
        }
        synchronized (this) { cache.clear(); }
    }
    @EventSubscriber public void reload(ReloadEvent event) { load(); }
    @EventSubscriber public void uploaded(TransferEvent event) {
        if (!enabled() || !"STOR".equals(event.getCommand())) return;
        try { lines(event.getTransferFile()); }
        catch (Exception e) { logger.debug("NFO prefetch skipped: {}", e.toString()); }
    }
    @EventSubscriber public void shutdown(MessageEvent event) {
        if ("SHUTDOWN".equals(event.getCommand())) stopPlugin("shutdown");
    }
    @Override public void stopPlugin(String reason) {
        closed = true;
        if (active == this) active = null;
        AnnotationProcessor.unprocess(this);
        worker.shutdownNow();
        synchronized (this) { cache.clear(); pending.clear(); }
    }

    List<String> lines(FileHandle file) throws Exception {
        if (!enabled() || !file.getName().toLowerCase(Locale.ROOT).endsWith(".nfo") || file.isLink()
                || file.isTransferring() || file.getXfertime() == -1 || file.getSize() < 1 || file.getSize() > NfoReader.MAX_BYTES)
            return List.of();
        Key key = Key.of(file);
        synchronized (this) {
            Entry entry = cache.get(key);
            if (entry != null && entry.expires > System.currentTimeMillis()) return entry.lines;
            if (!pending.add(key)) return List.of();
        }
        Settings requested = settings;
        try { worker.execute(() -> fetch(file, key, requested)); }
        catch (RejectedExecutionException e) { synchronized (this) { pending.remove(key); } }
        return List.of();
    }

    private void fetch(FileHandle file, Key key, Settings requested) {
        List<String> text = List.of();
        boolean success = false;
        try {
            if (!enabled() || settings != requested || file.isTransferring() || !key.equals(Key.of(file))) return;
            List<RemoteSlave> copies = new ArrayList<>(file.getSlaves());
            copies.sort(Comparator.comparing(RemoteSlave::getName));
            for (RemoteSlave slave : copies) {
                if (!slave.isAvailable() || slave.isRemerging() || !slave.getRenameQueue().isEmpty()) continue;
                long generation = slave.getConnectionGeneration();
                Capability capability = capabilities.get(slave.getName());
                if (capability == null || capability.generation != generation) {
                    String probe = slave.fetchIndex();
                    slave.sendCommand(new AsyncCommandArgument(probe, "maxpath", NfoReader.PROBE));
                    boolean supported = ((AsyncResponseMaxPath) slave.fetchResponseWithoutDisconnect(probe, 5000)).getMaxPath()
                            == NfoReader.CAPABILITY;
                    capability = new Capability(generation, supported);
                    capabilities.put(slave.getName(), capability);
                    if (capabilities.size() > 256) capabilities.remove(capabilities.keySet().iterator().next());
                }
                if (!capability.supported) continue;
                String index = slave.fetchIndex();
                slave.sendCommand(new AsyncCommandArgument(index, "nforead", new String[]{key.path, Long.toString(key.size)}));
                AsyncResponseLog response = (AsyncResponseLog) slave.fetchResponseWithoutDisconnect(index, 5000);
                if (response.getError() != null || response.getContent() == null) continue;
                if (closed || settings != requested || file.isTransferring() || slave.getConnectionGeneration() != generation
                        || !key.equals(Key.of(file))) return;
                text = safeLines(response.getContent(), requested.maxLines);
                success = true;
                break;
            }
        } catch (Exception e) { logger.debug("NFO background read skipped: path={} reason={}", key.path, e.toString()); }
        finally {
            synchronized (this) {
                pending.remove(key);
                if (!closed && requested == settings) {
                    cache.put(key, new Entry(text, System.currentTimeMillis() + (success ? requested.ttlMillis : 60000)));
                    while (cache.size() > requested.entries) cache.remove(cache.keySet().iterator().next());
                }
            }
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
