package org.drftpd.links.master;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/** Coalesces VFS bursts without doing disk/network work on event threads. */
final class LinkReconciler implements AutoCloseable {
    private static final Logger logger = LogManager.getLogger(LinkReconciler.class);
    private static final int CAPACITY = 2048;
    private final Map<String, Integer> pending = new LinkedHashMap<>();
    private final Predicate<String> reconcile;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "Link Reconciliation");
        thread.setDaemon(true);
        return thread;
    });
    private boolean closed;
    private long lastOverflow;

    LinkReconciler(Predicate<String> reconcile) { this.reconcile = reconcile; }
    void start() { worker.scheduleWithFixedDelay(this::drain, 2, 2, TimeUnit.SECONDS); }

    synchronized void request(String path) {
        if (closed || path.equals("/")) return;
        if (pending.containsKey(path)) return;
        if (pending.size() >= CAPACITY) {
            long now = System.currentTimeMillis();
            if (now - lastOverflow > 60000) {
                lastOverflow = now;
                logger.warn("Link reconciliation queue full; use SITE FIXLINKS after the VFS burst for skipped paths");
            }
            return;
        }
        pending.put(path, 0);
    }

    void drain() {
        Map<String, Integer> batch = new LinkedHashMap<>();
        synchronized (this) {
            if (closed) return;
            var iterator = pending.entrySet().iterator();
            while (iterator.hasNext() && batch.size() < 32) {
                var entry = iterator.next();
                batch.put(entry.getKey(), entry.getValue());
                iterator.remove();
            }
        }
        for (var entry : batch.entrySet()) {
            synchronized (this) { if (closed) return; }
            boolean done = false;
            try { done = reconcile.test(entry.getKey()); }
            catch (RuntimeException e) { logger.warn("Link reconciliation failed for {}", entry.getKey(), e); }
            if (!done) {
                synchronized (this) {
                    if (!closed && entry.getValue() < 14 && pending.size() < CAPACITY) {
                        pending.putIfAbsent(entry.getKey(), entry.getValue() + 1);
                    } else {
                        logger.debug("Link status still unknown for {}; waiting for the next VFS/upload event", entry.getKey());
                    }
                }
            }
        }
    }

    @Override public synchronized void close() {
        closed = true;
        pending.clear();
        worker.shutdownNow();
    }
}
