/*
 * This file is part of DrFTPD, Distributed FTP Daemon.
 *
 * DrFTPD is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * DrFTPD is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with DrFTPD; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.drftpd.master.event;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bushe.swing.event.ThreadSafeEventService;
import org.drftpd.master.vfs.event.VirtualFileSystemEvent;
import org.drftpd.master.vfs.event.VirtualFileSystemInodeCreatedEvent;
import org.drftpd.master.vfs.event.VirtualFileSystemInodeDeletedEvent;
import org.drftpd.master.vfs.event.VirtualFileSystemLastModifiedEvent;
import org.drftpd.master.vfs.event.VirtualFileSystemSizeEvent;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

/**
 * @author djb61
 * @version $Id$
 */
public class AsyncThreadSafeEventService extends ThreadSafeEventService {

    private static final Logger logger = LogManager.getLogger(AsyncThreadSafeEventService.class);
    private static final String REMERGE_THREAD_PREFIX = "RemergeThread - ";
    private static final AtomicInteger EVENT_HANDLER_SEQUENCE = new AtomicInteger();
    private static final ThreadLocal<Boolean> EVENT_HANDLER_THREAD =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    private final PriorityBlockingQueue<QueuedAsyncEvent> _eventQueue = new PriorityBlockingQueue<>();
    private final ConcurrentMap<String, QueuedAsyncEvent> _coalescedRemergeUpdates = new ConcurrentHashMap<>();
    private final AtomicInteger _effectiveQueueSize = new AtomicInteger();
    private final AtomicInteger _activeEvents = new AtomicInteger();
    private final EventHandler eventHandler = new EventHandler();

    public AsyncThreadSafeEventService() {
        this(true);
    }

    AsyncThreadSafeEventService(boolean startHandler) {
        super();
        if (startHandler) {
            Thread thread = new Thread(eventHandler,
                    "AsyncEventHandler-" + EVENT_HANDLER_SEQUENCE.incrementAndGet());
            thread.start();
        }
    }

    public static boolean isEventHandlerThread() {
        return EVENT_HANDLER_THREAD.get();
    }

    static boolean enterEventHandlerThread() {
        boolean wasEventHandler = EVENT_HANDLER_THREAD.get();
        EVENT_HANDLER_THREAD.set(Boolean.TRUE);
        return wasEventHandler;
    }

    static void leaveEventHandlerThread(boolean wasEventHandler) {
        if (wasEventHandler) {
            EVENT_HANDLER_THREAD.set(Boolean.TRUE);
        } else {
            EVENT_HANDLER_THREAD.remove();
        }
    }

    public void publishAsync(Object event) {
        enqueue(new QueuedAsyncEvent(event));
    }

    public void publishAsync(Type genericType, Object event) {
        enqueue(new QueuedAsyncEvent(genericType, event));
    }

    public void publishAsync(String topicName, Object eventObj) {
        enqueue(new QueuedAsyncEvent(topicName, eventObj));
    }

    public int getQueueSize() {
        return _effectiveQueueSize.get() + _activeEvents.get();
    }

    private void enqueue(QueuedAsyncEvent queuedEvent) {
        String coalesceKey = queuedEvent.getCoalesceKey();
        if (coalesceKey != null) {
            _coalescedRemergeUpdates.compute(coalesceKey, (key, existing) -> {
                if (existing != null && existing.isPending()) {
                    existing.replaceEvent(queuedEvent.getEvent());
                    return existing;
                }
                enqueueNew(queuedEvent);
                return queuedEvent;
            });
            return;
        }

        String barrierPath = getCoalescingBarrierPath(queuedEvent.getEvent());
        if (barrierPath != null) {
            cancelCoalescedUpdate(barrierPath);
        }
        enqueueNew(queuedEvent);
    }

    private void enqueueNew(QueuedAsyncEvent queuedEvent) {
        _effectiveQueueSize.incrementAndGet();
        _eventQueue.add(queuedEvent);
    }

    private void cancelCoalescedUpdate(String path) {
        _coalescedRemergeUpdates.computeIfPresent(path, (key, existing) -> {
            if (existing.cancel()) {
                _effectiveQueueSize.decrementAndGet();
            }
            return null;
        });
    }

    private static String getCoalescingBarrierPath(Object event) {
        if (!(event instanceof VirtualFileSystemEvent vfsEvent)) {
            return null;
        }
        if (event instanceof VirtualFileSystemSizeEvent
                || event instanceof VirtualFileSystemLastModifiedEvent
                || event instanceof VirtualFileSystemInodeCreatedEvent
                || event instanceof VirtualFileSystemInodeDeletedEvent) {
            return vfsEvent.getImmutableInode().getPath();
        }
        return null;
    }

    private QueuedAsyncEvent takeNextPendingEvent() throws InterruptedException {
        while (true) {
            QueuedAsyncEvent queuedEvent = _eventQueue.take();
            String coalesceKey = queuedEvent.getCoalesceKey();
            if (coalesceKey != null) {
                _coalescedRemergeUpdates.remove(coalesceKey, queuedEvent);
            }
            if (queuedEvent.claim()) {
                _effectiveQueueSize.decrementAndGet();
                _activeEvents.incrementAndGet();
                return queuedEvent;
            }
        }
    }

    QueuedAsyncEvent pollNextPendingEvent() {
        while (true) {
            QueuedAsyncEvent queuedEvent = _eventQueue.poll();
            if (queuedEvent == null) {
                return null;
            }
            String coalesceKey = queuedEvent.getCoalesceKey();
            if (coalesceKey != null) {
                _coalescedRemergeUpdates.remove(coalesceKey, queuedEvent);
            }
            if (queuedEvent.claim()) {
                _effectiveQueueSize.decrementAndGet();
                return queuedEvent;
            }
        }
    }

    public String getQueueSummary() {
        StringBuilder builder = new StringBuilder();
        Map<String, Map<String, Integer>> countEventPerSlave = new HashMap<>();
        Map<String, Integer> countEventNotFromSlave = new HashMap<>();

        logger.info("FIFO PROCESSING STATS - EVENT QUEUE SIZE={}", getQueueSize());
        for (QueuedAsyncEvent event : _eventQueue) {
            if (!event.isPending()) {
                continue;
            }
            String eventName = event.getEvent().getClass().getName();
            String sourceThreadName = event.getSourceThreadName();
            if (sourceThreadName.startsWith("RemoteSlave - ")) {
                String slaveName = sourceThreadName.substring(14);
                countEventPerSlave.computeIfAbsent(slaveName, ignored -> new HashMap<>())
                        .merge(eventName, 1, Integer::sum);
            } else {
                countEventNotFromSlave.merge(eventName, 1, Integer::sum);
            }
        }

        Map<String, ConcurrentMap<String, DurationStats>> durationEventPerSlave =
                eventHandler.getDurationEventPerSlave();
        Map<String, DurationStats> durationEventNotFromSlave =
                eventHandler.getDurationEventNotPerSlave();

        java.util.HashSet<String> allSlaves = new java.util.HashSet<>();
        allSlaves.addAll(countEventPerSlave.keySet());
        allSlaves.addAll(durationEventPerSlave.keySet());
        for (String slave : allSlaves) {
            Map<String, Integer> eventInQueue = countEventPerSlave.get(slave);
            if (eventInQueue != null && !eventInQueue.isEmpty()) {
                for (Map.Entry<String, Integer> entry : eventInQueue.entrySet()) {
                    logger.info("FIFO PROCESSING STATS - SLAVE={} EVENT QUEUED={} TOTAL={}",
                            slave, entry.getKey(), entry.getValue());
                    builder.append("FIFO PROCESSING STATS - SLAVE=").append(slave)
                            .append(" EVENT QUEUED=").append(entry.getKey())
                            .append(" TOTAL=").append(entry.getValue()).append('\n');
                }
            } else {
                logger.info("FIFO PROCESSING STATS - NO EVENTS QUEUED FOR SLAVE={}", slave);
                builder.append("FIFO PROCESSING STATS - NO EVENTS QUEUED FOR SLAVE=")
                        .append(slave).append('\n');
            }

            long allSlaveCount = 0L;
            Map<String, DurationStats> eventProcessed = durationEventPerSlave.get(slave);
            if (eventProcessed != null && !eventProcessed.isEmpty()) {
                for (Map.Entry<String, DurationStats> entry : eventProcessed.entrySet()) {
                    DurationSnapshot snapshot = entry.getValue().snapshot();
                    logger.info("FIFO PROCESSING STATS - SLAVE={} EVENT PROCESSED={} TOTAL={} "
                                    + "MEAN DURATION={} MAX DURATION={}",
                            slave, entry.getKey(), snapshot.count(), snapshot.mean(), snapshot.max());
                    builder.append("FIFO PROCESSING STATS - SLAVE=").append(slave)
                            .append(" EVENT PROCESSED=").append(entry.getKey())
                            .append(" TOTAL=").append(snapshot.count())
                            .append(" MEAN DURATION=").append(snapshot.mean())
                            .append(" MAX DURATION=").append(snapshot.max()).append('\n');
                    allSlaveCount += snapshot.count();
                }
                logger.info("FIFO PROCESSING STATS - SLAVE={} EVENTS PROCESSED TOTAL={}",
                        slave, allSlaveCount);
                builder.append("FIFO PROCESSING STATS - SLAVE=").append(slave)
                        .append(" EVENTS PROCESSED TOTAL=").append(allSlaveCount).append('\n');
            } else {
                logger.info("FIFO PROCESSING STATS - SLAVE={} NO EVENTS PROCESSED", slave);
                builder.append("FIFO PROCESSING STATS - SLAVE=").append(slave)
                        .append(" NO EVENTS PROCESSED\n\n");
            }
        }

        if (!countEventNotFromSlave.isEmpty()) {
            long allCount = 0L;
            logger.info("FIFO PROCESSING STATS - NOT ORIGINATING FROM SLAVES TOTAL EVENT CATEGORY={}",
                    countEventNotFromSlave.size());
            builder.append("FIFO PROCESSING STATS - NOT ORIGINATING FROM SLAVES\n");
            for (Map.Entry<String, Integer> entry : countEventNotFromSlave.entrySet()) {
                logger.info("FIFO PROCESSING STATS - EVENT QUEUED={} TOTAL={}",
                        entry.getKey(), entry.getValue());
                builder.append("FIFO PROCESSING STATS - EVENT QUEUED=").append(entry.getKey())
                        .append(" TOTAL=").append(entry.getValue()).append('\n');
                allCount += entry.getValue();
            }
            logger.info("FIFO PROCESSING STATS - EVENT QUEUED TOTAL={}", allCount);
            builder.append("FIFO PROCESSING STATS - EVENT QUEUED TOTAL=")
                    .append(allCount).append("\n\n");
        } else {
            logger.info("FIFO PROCESSING STATS - NO EVENTS NOT ORIGINATING FROM SLAVES IN QUEUE");
            builder.append("FIFO PROCESSING STATS - NO EVENTS NOT ORIGINATING FROM SLAVES IN QUEUE\n\n");
        }

        if (!durationEventNotFromSlave.isEmpty()) {
            long allCount = 0L;
            for (Map.Entry<String, DurationStats> entry : durationEventNotFromSlave.entrySet()) {
                DurationSnapshot snapshot = entry.getValue().snapshot();
                logger.info("FIFO PROCESSING STATS - EVENT PROCESSED={} TOTAL={} "
                                + "MEAN DURATION={} MAX DURATION={}",
                        entry.getKey(), snapshot.count(), snapshot.mean(), snapshot.max());
                builder.append("FIFO PROCESSING STATS - EVENT PROCESSED=").append(entry.getKey())
                        .append(" TOTAL=").append(snapshot.count())
                        .append(" MEAN DURATION=").append(snapshot.mean())
                        .append(" MAX DURATION=").append(snapshot.max()).append('\n');
                allCount += snapshot.count();
            }
            logger.info("FIFO PROCESSING STATS - EVENT PROCESSED TOTAL={}", allCount);
            builder.append("FIFO PROCESSING STATS - EVENT PROCESSED TOTAL=")
                    .append(allCount).append("\n\n");
        } else {
            logger.info("FIFO PROCESSING STATS - NO EVENTS PROCESSED");
            builder.append("FIFO PROCESSING STATS - NO EVENTS PROCESSED\n\n");
        }

        if (eventHandler.currentSourceThreadName != null) {
            long duration = System.currentTimeMillis() - eventHandler.lastTake;
            logger.info("FIFO PROCESSING STATS - EVENT BEING PROCESSED - THREAD={} EVENT={} SINCE={}",
                    eventHandler.currentSourceThreadName, eventHandler.currentEventName, duration);
            builder.append("FIFO PROCESSING STATS - EVENT BEING PROCESSED - THREAD=")
                    .append(eventHandler.currentSourceThreadName).append(" EVENT=")
                    .append(eventHandler.currentEventName).append(" SINCE=").append(duration)
                    .append("\n\n");
        } else {
            logger.info("FIFO PROCESSING STATS - NO EVENT BEING PROCESSED");
            builder.append("FIFO PROCESSING STATS - NO EVENT BEING PROCESSED\n\n");
        }
        return builder.toString();
    }

    static final class QueuedAsyncEvent implements Comparable<QueuedAsyncEvent> {
        private static final AtomicLong SEQUENCE = new AtomicLong();

        private volatile Object _event;
        private final String _topic;
        private final Type _genericType;
        private final String sourceThreadName = Thread.currentThread().getName();
        private final long sequence = SEQUENCE.getAndIncrement();
        private final String coalesceKey;
        private final AtomicBoolean pending = new AtomicBoolean(true);

        private QueuedAsyncEvent(Object event) {
            _topic = null;
            _genericType = null;
            _event = event;
            coalesceKey = buildCoalesceKey(event, sourceThreadName);
        }

        private QueuedAsyncEvent(String topic, Object event) {
            _topic = topic;
            _genericType = null;
            _event = event;
            coalesceKey = null;
        }

        private QueuedAsyncEvent(Type genericType, Object event) {
            _topic = null;
            _genericType = genericType;
            _event = event;
            coalesceKey = null;
        }

        Object getEvent() {
            return _event;
        }

        private void replaceEvent(Object event) {
            _event = event;
        }

        private String getTopic() {
            return _topic;
        }

        private Type getGenericType() {
            return _genericType;
        }

        String getSourceThreadName() {
            return sourceThreadName;
        }

        private String getCoalesceKey() {
            return coalesceKey;
        }

        private boolean isPending() {
            return pending.get();
        }

        private boolean claim() {
            return pending.compareAndSet(true, false);
        }

        private boolean cancel() {
            return pending.compareAndSet(true, false);
        }

        @Override
        public int compareTo(QueuedAsyncEvent other) {
            int priority = Boolean.compare(isRemergeEvent(), other.isRemergeEvent());
            return priority != 0 ? priority : Long.compare(sequence, other.sequence);
        }

        private boolean isRemergeEvent() {
            return sourceThreadName.startsWith(REMERGE_THREAD_PREFIX);
        }

        private static String buildCoalesceKey(Object event, String sourceThreadName) {
            if (!sourceThreadName.startsWith(REMERGE_THREAD_PREFIX)) {
                return null;
            }
            if (event instanceof VirtualFileSystemSizeEvent
                    || event instanceof VirtualFileSystemLastModifiedEvent) {
                return ((VirtualFileSystemEvent) event).getImmutableInode().getPath();
            }
            return null;
        }
    }

    static final class DurationStats {
        private final LongAdder count = new LongAdder();
        private final LongAdder total = new LongAdder();
        private final LongAccumulator max = new LongAccumulator(Long::max, 0L);

        void record(long duration) {
            count.increment();
            total.add(duration);
            max.accumulate(duration);
        }

        DurationSnapshot snapshot() {
            long countValue = count.sum();
            long totalValue = total.sum();
            return new DurationSnapshot(countValue, totalValue,
                    countValue == 0L ? 0.0D : (double) totalValue / countValue, max.get());
        }
    }

    record DurationSnapshot(long count, long total, double mean, long max) {
    }

    private class EventHandler implements Runnable {
        private final ConcurrentMap<String, ConcurrentMap<String, DurationStats>> durationEventPerSlave =
                new ConcurrentHashMap<>();
        private final ConcurrentMap<String, DurationStats> durationEventNotFromSlave = new ConcurrentHashMap<>();
        private volatile long lastTake;
        private volatile String currentEventName;
        private volatile String currentSourceThreadName;

        private Map<String, ConcurrentMap<String, DurationStats>> getDurationEventPerSlave() {
            return durationEventPerSlave;
        }

        private Map<String, DurationStats> getDurationEventNotPerSlave() {
            return durationEventNotFromSlave;
        }

        @Override
        public void run() {
            boolean wasEventHandler = enterEventHandlerThread();
            try {
                while (true) {
                    QueuedAsyncEvent queuedEvent = null;
                    try {
                        queuedEvent = takeNextPendingEvent();
                        lastTake = System.currentTimeMillis();
                        currentEventName = queuedEvent.getEvent() instanceof SlaveEvent
                                ? ((SlaveEvent) queuedEvent.getEvent()).getCommand()
                                : queuedEvent.getEvent().getClass().getName();
                        currentSourceThreadName = queuedEvent.getSourceThreadName();

                        if (queuedEvent.getTopic() != null) {
                            publish(queuedEvent.getTopic(), queuedEvent.getEvent());
                        } else if (queuedEvent.getGenericType() != null) {
                            publish(queuedEvent.getGenericType(), queuedEvent.getEvent());
                        } else {
                            publish(queuedEvent.getEvent());
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (Throwable t) {
                        if (queuedEvent == null) {
                            logger.error("FATAL ERROR EventHandler FIFO before an event was dequeued", t);
                        } else {
                            logger.error("FATAL ERROR EventHandler FIFO. ({}, {}, {})",
                                    queuedEvent.getTopic(), queuedEvent.getGenericType(), queuedEvent.getEvent(), t);
                        }
                    } finally {
                        if (queuedEvent != null && currentSourceThreadName != null) {
                            recordDuration(currentSourceThreadName, currentEventName,
                                    System.currentTimeMillis() - lastTake);
                        }
                        if (queuedEvent != null) {
                            _activeEvents.decrementAndGet();
                        }
                        currentSourceThreadName = null;
                        currentEventName = null;
                    }
                }
            } finally {
                leaveEventHandlerThread(wasEventHandler);
            }
        }

        private void recordDuration(String sourceThreadName, String eventName, long duration) {
            if (sourceThreadName.startsWith("RemoteSlave - ")) {
                String slaveName = sourceThreadName.substring(14);
                durationEventPerSlave.computeIfAbsent(slaveName, ignored -> new ConcurrentHashMap<>())
                        .computeIfAbsent(eventName, ignored -> new DurationStats())
                        .record(duration);
            } else {
                durationEventNotFromSlave.computeIfAbsent(eventName, ignored -> new DurationStats())
                        .record(duration);
            }
        }
    }
}
