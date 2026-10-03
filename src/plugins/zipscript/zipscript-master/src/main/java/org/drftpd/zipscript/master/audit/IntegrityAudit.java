package org.drftpd.zipscript.master.audit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bushe.swing.event.annotation.AnnotationProcessor;
import org.bushe.swing.event.annotation.EventSubscriber;
import org.drftpd.common.dynamicdata.KeyNotFoundException;
import org.drftpd.common.extensibility.PluginInterface;
import org.drftpd.common.network.AsyncCommandArgument;
import org.drftpd.common.util.ConfigLoader;
import org.drftpd.master.GlobalContext;
import org.drftpd.master.event.MessageEvent;
import org.drftpd.master.event.ReloadEvent;
import org.drftpd.master.indexation.AdvancedSearchParams;
import org.drftpd.master.sitebot.plugins.sysop.event.SysopEvent;
import org.drftpd.master.slavemanagement.RemoteSlave;
import org.drftpd.master.vfs.DirectoryHandle;
import org.drftpd.master.vfs.FileHandle;
import org.drftpd.master.vfs.event.ImmutableInodeHandle;
import org.drftpd.slave.IdleChecksumScanner;
import org.drftpd.slave.network.AsyncResponseChecksum;
import org.drftpd.slave.network.AsyncResponseMaxPath;
import org.drftpd.zipscript.common.sfv.SFVInfo;
import java.io.FileNotFoundException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Optional read-only scrubber. The shared timer only dispatches to this private worker. */
public class IntegrityAudit implements PluginInterface {
    private static final Logger logger = LogManager.getLogger(IntegrityAudit.class);
    private static final String TIMER = "integrity.dispatch";
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread t = new Thread(task, "Integrity Audit");
        t.setDaemon(true); t.setPriority(Thread.MIN_PRIORITY); return t;
    });
    private final AtomicBoolean queued = new AtomicBoolean();
    private final AtomicBoolean running = new AtomicBoolean();
    private final Map<String, Progress> progress = new HashMap<>();
    private volatile IntegrityConfig config;
    private AuditStore store;
    private long lastCommit, nextBackfill;
    private int roundRobin;

    public void startPlugin() {
        try { config = IntegrityConfig.read(ConfigLoader.loadPluginConfig("integrity.conf")); }
        catch (RuntimeException e) {
            logger.error("Invalid integrity.conf; CRC audit disabled until corrected and reloaded", e);
            config = IntegrityConfig.read(new Properties());
        }
        running.set(true);
        worker.execute(() -> {
            try { store = new AuditStore(Path.of("userdata", "integrity-audit")); }
            catch (Exception e) { logger.error("Integrity audit database could not be opened; audit disabled", e); }
        });
        AnnotationProcessor.process(this);
        GlobalContext.getGlobalContext().scheduleTimer(TIMER, getClass().getName(), this::dispatch, 30000, 1000);
        logger.info("Integrity audit loaded: enabled={}, intervalDays={}, idleSeconds={}",
                config.enabled(), config.intervalMillis() / 86400000, config.idleMillis() / 1000);
    }

    private void dispatch() {
        if (!running.get() || !queued.compareAndSet(false, true)) return;
        try {
            worker.execute(() -> {
                try { if (store != null && running.get()) tick(); }
                catch (Exception e) { logger.warn("Integrity background pass deferred", e); }
                finally { queued.set(false); }
            });
        } catch (RejectedExecutionException e) { queued.set(false); }
    }

    @EventSubscriber public void reload(ReloadEvent event) {
        try {
            config = IntegrityConfig.read(ConfigLoader.loadPluginConfig("integrity.conf"));
            logger.info("Integrity audit reloaded: enabled={}", config.enabled());
        } catch (RuntimeException e) { logger.error("Invalid integrity.conf; keeping previous configuration", e); }
    }
    @EventSubscriber public void shutdown(MessageEvent event) {
        if ("SHUTDOWN".equals(event.getCommand())) stopPlugin("shutdown");
    }
    public void stopPlugin(String reason) {
        if (!running.getAndSet(false)) return;
        AnnotationProcessor.unprocess(this);
        GlobalContext.getGlobalContext().cancelTimer(TIMER);
        worker.execute(() -> {
            try { if (store != null) store.close(); }
            catch (Exception e) { logger.error("Unable to close integrity database", e); }
        });
        worker.shutdown();
        try {
            if (!worker.awaitTermination(20, TimeUnit.SECONDS)) {
                logger.warn("Integrity worker is still finishing; the last committed checkpoint is recoverable");
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private void tick() throws Exception {
        long now = System.currentTimeMillis();
        if (config.backfill() && now >= nextBackfill
                && GlobalContext.getEventServiceSlowest().getQueueSize() < 5000) {
            backfill(now);
        }
        if (config.enabled()) {
            List<RemoteSlave> slaves = new ArrayList<>(GlobalContext.getGlobalContext().getSlaveManager().getSlaves());
            slaves.sort(Comparator.comparing(RemoteSlave::getName));
            for (int i = 0; i < config.steps() && running.get() && config.enabled() && !slaves.isEmpty(); i++) {
                RemoteSlave slave = slaves.get(Math.floorMod(roundRobin++, slaves.size()));
                if (!config.slaves().isEmpty() && !config.slaves().contains(slave.getName())) continue;
                Progress state = progress.computeIfAbsent(slave.getName(), key -> new Progress());
                try { step(slave, state, now); }
                catch (Exception e) {
                    state.retryAfter = now + 60000;
                    state.pending = null;
                    logger.warn("Idle audit deferred: slave={} reason={}", slave.getName(), e.toString());
                }
            }
        }
        if (now - lastCommit >= 30000) { store.commit(); lastCommit = now; }
    }

    private void backfill(long now) throws Exception {
        Cursor cursor = store.get("catalogue-cursor", Cursor.class);
        if (cursor == null) cursor = new Cursor("", 0);
        if (now < cursor.nextPass) { nextBackfill = cursor.nextPass; return; }
        AdvancedSearchParams params = page(cursor.after, config.backfillBatch(), AdvancedSearchParams.InodeType.DIRECTORY);
        Map<String, String> paths = GlobalContext.getGlobalContext().getIndexEngine().advancedFind(
                GlobalContext.getGlobalContext().getRoot(), params, "CATALOGUE-backfill");
        for (String path : paths.keySet()) {
            if (!running.get()) break;
            try {
                DirectoryHandle dir = new DirectoryHandle(path);
                GlobalContext.getGlobalContext().getIndexEngine().updateInode(new ImmutableInodeHandle(dir.getInode(), dir.getPath()));
            } catch (FileNotFoundException ignored) { }
            cursor = new Cursor(path, 0);
        }
        if (paths.isEmpty()) cursor = new Cursor("", now + 86400000L);
        store.put("catalogue-cursor", cursor);
        nextBackfill = now + 30000;
    }

    void step(RemoteSlave slave, Progress state, long now) throws Exception {
        long generation = slave.getConnectionGeneration();
        if (state.generation != generation) {
            state.generation = generation;
            state.supported = null;
            state.pending = null;
            state.quietSince = now;
        }
        if (!slave.isOnline() || slave.isRemerging()) {
            state.quietSince = now; state.supported = null; state.pending = null; return;
        }
        var status = slave.getSlaveStatus();
        long traffic = status.getBytesReceived() + status.getBytesSent();
        if (status.getTransfers() != 0 || state.traffic != traffic) {
            state.traffic = traffic; state.quietSince = now; return;
        }
        if (now - state.quietSince < config.idleMillis() || now < state.retryAfter) return;
        if (state.supported == null) {
            String index = slave.fetchIndex();
            // Existing command/response classes: an old slave returns its normal maximum path length.
            slave.sendCommand(new AsyncCommandArgument(index, "maxpath", "idle-crc-audit-v1"));
            state.supported = ((AsyncResponseMaxPath) slave.fetchResponseWithoutDisconnect(index, 15000)).getMaxPath()
                    == IdleChecksumScanner.CAPABILITY;
            if (!state.supported) logger.info("Idle audit skipped for older slave {} (capability not supported)", slave.getName());
        }
        if (!state.supported) return;
        if (state.cursor == null) {
            state.cursor = store.get("cursor:" + slave.getName(), Cursor.class);
            if (state.cursor == null) state.cursor = new Cursor("", 0);
        }
        if (now < state.cursor.nextPass) return;
        if (state.pending == null) select(slave, state, now);
        Pending pending = state.pending;
        if (pending == null || !running.get() || !config.enabled()) return;
        if (slave.getConnectionGeneration() != generation) return;
        FileHandle file = new FileHandle(pending.path);
        if (!unchanged(file, slave, pending) || !config.includes(file.getPath())) {
            state.pending = null; return;
        }
        String index = slave.fetchIndex();
        slave.sendCommand(new AsyncCommandArgument(index, "idlecrc",
                new String[]{file.getPath(), Long.toString(pending.size)}));
        long measured = ((AsyncResponseChecksum) slave.fetchResponseWithoutDisconnect(index, 15000)).getChecksum();
        if (measured == IdleChecksumScanner.BUSY) { state.quietSince = now; return; }
        if (measured == IdleChecksumScanner.MORE) return;
        if (measured < 0 || measured > 0xffffffffL) throw new IllegalStateException("Invalid audit CRC response");
        if (slave.getConnectionGeneration() != generation || !unchanged(file, slave, pending)
                || slave.isRemerging()) { state.pending = null; return; }
        if (!pending.reference.equals(reference(file, pending.previous))) {
            state.pending = null;
            return;
        }
        finish(slave, file, pending, measured, System.currentTimeMillis());
        state.cursor = new Cursor(file.getPath(), 0);
        store.put("cursor:" + slave.getName(), state.cursor);
        state.pending = null;
    }

    private void select(RemoteSlave slave, Progress state, long now) throws Exception {
        AdvancedSearchParams params = page(state.cursor.after, config.scanBatch(), AdvancedSearchParams.InodeType.FILE);
        params.setSlaves(Set.of(slave.getName()));
        Map<String, String> files = GlobalContext.getGlobalContext().getIndexEngine().advancedFind(
                GlobalContext.getGlobalContext().getRoot(), params, "INTEGRITY-candidates");
        for (String path : files.keySet()) {
            if (!running.get()) return;
            FileHandle file = new FileHandle(path);
            try {
                if (config.includes(path) && !file.isUploading() && file.getXfertime() != -1
                        && file.getSize() > 0 && now - file.lastModified() >= config.minimumAgeMillis()
                        && file.getSlaves().contains(slave)) {
                    AuditResult previous = store.get(id(slave.getName(), path), AuditResult.class);
                    Reference reference = reference(file, previous);
                    if (previous != null && previous.sameFile(file.getSize(), file.lastModified())) {
                        AuditFailure.record(file, slave.getName(), previous.measured(), previous.mismatch());
                    }
                    if (previous == null || !previous.sameFile(file.getSize(), file.lastModified())
                            || now - previous.checkedAt() >= config.intervalMillis()
                            || (reference.crc != null && previous.expected() != reference.crc)) {
                        state.pending = new Pending(path, file.getSize(), file.lastModified(), file.getXfertime(), reference, previous);
                        return;
                    }
                }
            } catch (FileNotFoundException ignored) { }
            state.cursor = new Cursor(path, 0);
        }
        if (files.isEmpty()) state.cursor = new Cursor("", now + 3600000);
        store.put("cursor:" + slave.getName(), state.cursor);
    }

    Reference reference(FileHandle file, AuditResult previous) throws Exception {
        try {
            SFVInfo info = file.getParent().getPluginMetaData(SFVInfo.SFVINFO);
            if (info.getSFVFileName() != null && info.getSFVFileName().equalsIgnoreCase(file.getName())) {
                return new Reference(info.getChecksum(), "sfv-metadata");
            }
            Long expected = info.getEntries().get(file.getName());
            if (expected != null) return new Reference(expected, "sfv");
        } catch (KeyNotFoundException ignored) { }
        if (previous != null && previous.sameFile(file.getSize(), file.lastModified())) {
            return new Reference(previous.expected(), previous.source());
        }
        long cached = file.getCheckSumCached();
        return new Reference(cached == 0 ? null : cached, "vfs-cache");
    }

    private void finish(RemoteSlave slave, FileHandle file, Pending pending, long measured, long now) throws Exception {
        AuditResult result = AuditResult.measured(slave.getName(), file.getPath(), pending.size, pending.modified,
                pending.reference.crc, pending.reference.source, measured, now);
        AuditFailure.record(file, slave.getName(), measured, result.mismatch());
        store.put(id(slave.getName(), file.getPath()), result);
        // A global VFS checksum is only filled when every known copy agrees.
        boolean allVerified = !result.mismatch();
        for (RemoteSlave copy : file.getSlaves()) {
            AuditResult verified = store.get(id(copy.getName(), file.getPath()), AuditResult.class);
            allVerified &= verified != null && verified.sameFile(file.getSize(), file.lastModified())
                    && !verified.mismatch() && verified.measured() == measured;
        }
        if (allVerified && !AuditFailure.isBad(file) && file.getCheckSumCached() != measured) file.setCheckSum(measured);
        DirectoryHandle parent = file.getParent();
        GlobalContext.getEventService().publishAsync(
                new org.drftpd.master.vfs.event.VirtualFileSystemInodeRefreshEvent(parent.getInode(), parent.getPath()));
        if (result.newMismatch(pending.previous)) {
            store.commit();
            String message = String.format(Locale.ROOT,
                    "CRC MISMATCH slave=%s path=%s expected=%08X actual=%08X reference=%s (file kept)",
                    slave.getName(), file.getPath(), result.expected(), measured, result.source());
            logger.error(message);
            GlobalContext.getEventService().publishAsync(new SysopEvent("IntegrityAudit", "BACKGROUND CRC", message, false, false));
        } else {
            logger.info("Idle CRC audit: slave={} path={} state={} reference={} crc={}", slave.getName(), file.getPath(),
                    result.mismatch() ? "mismatch" : result.source().equals("baseline") ? "baseline" : "verified",
                    result.source(), Long.toHexString(measured));
        }
    }

    private static boolean unchanged(FileHandle file, RemoteSlave slave, Pending pending) throws FileNotFoundException {
        return !file.isUploading() && file.getSize() == pending.size && file.lastModified() == pending.modified
                && file.getXfertime() == pending.xfertime && file.getSlaves().contains(slave);
    }
    private static String id(String slave, String path) { return "file:" + slave + "\n" + path; }
    private static AdvancedSearchParams page(String after, int limit, AdvancedSearchParams.InodeType type) {
        AdvancedSearchParams params = new AdvancedSearchParams();
        params.setAfterPath(after); params.setLimit(limit); params.setInodeType(type);
        return params;
    }
    record Cursor(String after, long nextPass) { }
    record Reference(Long crc, String source) { }
    private record Pending(String path, long size, long modified, long xfertime, Reference reference, AuditResult previous) { }
    static final class Progress {
        long traffic = -1, generation = -1, quietSince = System.currentTimeMillis(), retryAfter;
        Boolean supported;
        Cursor cursor;
        Pending pending;
    }
}
