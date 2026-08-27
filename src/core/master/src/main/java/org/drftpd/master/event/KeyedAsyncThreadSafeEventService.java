/*
 * This file is part of DrFTPD, Distributed FTP Daemon.
 *
 * DrFTPD is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 */
package org.drftpd.master.event;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

/**
 * Asynchronous event service that serializes events sharing a key and uses a
 * shared adaptive worker pool for independent keys.
 */
public final class KeyedAsyncThreadSafeEventService extends AsyncThreadSafeEventService {

    private static final Logger logger = LogManager.getLogger(KeyedAsyncThreadSafeEventService.class);
    private static final String GLOBAL_KEY = "__global__";

    private final ConcurrentMap<String, EventLane> _lanes = new ConcurrentHashMap<>();
    private final BlockingQueue<EventLane> _readyLanes = new LinkedBlockingQueue<>();
    private final ConcurrentMap<String, DurationStats> _durationStats = new ConcurrentHashMap<>();
    private final AtomicInteger _outstandingEvents = new AtomicInteger();
    private final AtomicInteger _activeEvents = new AtomicInteger();
    private final AtomicLong _sequence = new AtomicLong();
    private final ThreadPoolExecutor _workers;

    public KeyedAsyncThreadSafeEventService(String threadPrefix) {
        this(threadPrefix, defaultCoreWorkers(), defaultMaxWorkers());
    }

    KeyedAsyncThreadSafeEventService(String threadPrefix, int coreWorkers, int maxWorkers) {
        super(false);
        validateWorkerCounts(coreWorkers, maxWorkers);
        _workers = new ThreadPoolExecutor(coreWorkers, maxWorkers, 60L, TimeUnit.SECONDS,
                new SynchronousQueue<>(), new NamedThreadFactory(threadPrefix + "-Worker"),
                new ThreadPoolExecutor.AbortPolicy());
        Thread dispatcher = new Thread(this::dispatch, threadPrefix + "-Dispatcher");
        dispatcher.setDaemon(true);
        dispatcher.start();
        logger.info("Adaptive keyed event workers started: name={} cpus={} core={} max={}",
                threadPrefix, Runtime.getRuntime().availableProcessors(), coreWorkers, maxWorkers);
    }

    public void configure(Properties properties, String coreProperty, String maxProperty) {
        int cpus = Runtime.getRuntime().availableProcessors();
        int coreWorkers = parseWorkerCount(properties.getProperty(coreProperty, "auto"),
                clamp(cpus / 8, 2, 8), coreProperty);
        int maxWorkers = parseWorkerCount(properties.getProperty(maxProperty, "auto"),
                clamp(cpus / 4, 4, 16), maxProperty);
        if (maxWorkers < coreWorkers) {
            logger.warn("{} ({}) is below {} ({}); using {} for both", maxProperty, maxWorkers,
                    coreProperty, coreWorkers, coreWorkers);
            maxWorkers = coreWorkers;
        }
        configureWorkers(coreWorkers, maxWorkers);
    }

    public synchronized void configureWorkers(int coreWorkers, int maxWorkers) {
        validateWorkerCounts(coreWorkers, maxWorkers);
        if (coreWorkers < _workers.getCorePoolSize()) {
            _workers.setCorePoolSize(coreWorkers);
        }
        _workers.setMaximumPoolSize(maxWorkers);
        _workers.setCorePoolSize(coreWorkers);
        logger.info("Adaptive keyed event workers configured: core={} max={}", coreWorkers, maxWorkers);
    }

    @Override
    public void publishAsync(Object event) {
        enqueue(new QueuedEvent(null, null, event));
    }

    @Override
    public void publishAsync(Type genericType, Object event) {
        enqueue(new QueuedEvent(null, genericType, event));
    }

    @Override
    public void publishAsync(String topicName, Object event) {
        enqueue(new QueuedEvent(topicName, null, event));
    }

    @Override
    public int getQueueSize() {
        return _outstandingEvents.get();
    }

    @Override
    public String getQueueSummary() {
        StringBuilder builder = new StringBuilder();
        builder.append("FIFO PROCESSING STATS - KEYED RELEASE OUTSTANDING=")
                .append(getQueueSize()).append(" ACTIVE EVENTS=")
                .append(_activeEvents.get()).append(" ACTIVE LANES=")
                .append(_lanes.size()).append(" WORKERS=")
                .append(_workers.getActiveCount()).append('/')
                .append(_workers.getPoolSize()).append('/')
                .append(_workers.getMaximumPoolSize()).append('\n');
        for (Map.Entry<String, DurationStats> entry : _durationStats.entrySet()) {
            DurationSnapshot snapshot = entry.getValue().snapshot();
            builder.append("FIFO PROCESSING STATS - EVENT PROCESSED=")
                    .append(entry.getKey()).append(" TOTAL=")
                    .append(snapshot.count()).append(" MEAN DURATION=")
                    .append(snapshot.mean()).append(" MAX DURATION=")
                    .append(snapshot.max()).append('\n');
        }
        return builder.toString();
    }

    int getActiveWorkerCount() {
        return _workers.getActiveCount();
    }

    public WorkerPoolStatus getWorkerPoolStatus(String name) {
        int activeEvents = _activeEvents.get();
        return new WorkerPoolStatus(name, _workers.getCorePoolSize(), _workers.getMaximumPoolSize(),
                _workers.getPoolSize(), _workers.getActiveCount(),
                Math.max(0, _outstandingEvents.get() - activeEvents));
    }

    private void enqueue(QueuedEvent event) {
        String key = event.event() instanceof KeyedEvent
                ? normalizeKey(((KeyedEvent) event.event()).getEventKey()) : GLOBAL_KEY;
        _outstandingEvents.incrementAndGet();
        _lanes.compute(key, (ignored, lane) -> {
            EventLane target = lane == null ? new EventLane(key) : lane;
            if (target.add(event)) {
                _readyLanes.add(target);
            }
            return target;
        });
    }

    private void dispatch() {
        while (!Thread.currentThread().isInterrupted()) {
            EventLane lane = null;
            try {
                lane = _readyLanes.take();
                EventLane selectedLane = lane;
                _workers.execute(() -> processOne(selectedLane));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RejectedExecutionException e) {
                if (lane != null) {
                    _readyLanes.add(lane);
                }
                try {
                    Thread.sleep(1L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void processOne(EventLane lane) {
        QueuedEvent queuedEvent = lane.poll();
        if (queuedEvent == null) {
            finishLane(lane);
            return;
        }

        _activeEvents.incrementAndGet();
        long started = System.currentTimeMillis();
        boolean wasEventHandler = enterEventHandlerThread();
        try {
            if (queuedEvent.topic() != null) {
                publish(queuedEvent.topic(), queuedEvent.event());
            } else if (queuedEvent.genericType() != null) {
                publish(queuedEvent.genericType(), queuedEvent.event());
            } else {
                publish(queuedEvent.event());
            }
        } catch (Throwable t) {
            logger.error("FATAL ERROR keyed event handler. ({}, {}, {})",
                    queuedEvent.topic(), queuedEvent.genericType(), queuedEvent.event(), t);
        } finally {
            leaveEventHandlerThread(wasEventHandler);
            _durationStats.computeIfAbsent(queuedEvent.event().getClass().getName(),
                    ignored -> new DurationStats()).record(System.currentTimeMillis() - started);
            _activeEvents.decrementAndGet();
            _outstandingEvents.decrementAndGet();
            finishLane(lane);
        }
    }

    private void finishLane(EventLane lane) {
        if (lane.finishAndNeedsReschedule()) {
            _readyLanes.add(lane);
            return;
        }
        _lanes.computeIfPresent(lane.key(), (ignored, current) ->
                current == lane && current.isIdle() ? null : current);
    }

    private static int defaultCoreWorkers() {
        return clamp(Runtime.getRuntime().availableProcessors() / 8, 2, 8);
    }

    private static int defaultMaxWorkers() {
        return clamp(Runtime.getRuntime().availableProcessors() / 4, 4, 16);
    }

    private static int parseWorkerCount(String value, int automatic, String property) {
        if (value == null || value.equalsIgnoreCase("auto")) {
            return automatic;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1) {
                throw new NumberFormatException("must be positive");
            }
            return parsed;
        } catch (NumberFormatException e) {
            logger.warn("Invalid {} value [{}]; using automatic value {}", property, value, automatic);
            return automatic;
        }
    }

    private static void validateWorkerCounts(int coreWorkers, int maxWorkers) {
        if (coreWorkers < 1 || maxWorkers < coreWorkers) {
            throw new IllegalArgumentException("Invalid keyed event worker counts");
        }
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static String normalizeKey(String key) {
        return key == null || key.isBlank() ? GLOBAL_KEY : key;
    }

    private final class EventLane {
        private final String _key;
        private final PriorityBlockingQueue<QueuedEvent> _events = new PriorityBlockingQueue<>();
        private boolean _scheduled;

        private EventLane(String key) {
            _key = key;
        }

        private synchronized boolean add(QueuedEvent event) {
            _events.add(event);
            if (_scheduled) {
                return false;
            }
            _scheduled = true;
            return true;
        }

        private synchronized QueuedEvent poll() {
            return _events.poll();
        }

        private synchronized boolean finishAndNeedsReschedule() {
            if (!_events.isEmpty()) {
                return true;
            }
            _scheduled = false;
            return false;
        }

        private synchronized boolean isIdle() {
            return !_scheduled && _events.isEmpty();
        }

        private String key() {
            return _key;
        }
    }

    private final class QueuedEvent implements Comparable<QueuedEvent> {
        private final String _topic;
        private final Type _genericType;
        private final Object _event;
        private final long _sequenceNumber;

        private QueuedEvent(String topic, Type genericType, Object event) {
            _topic = topic;
            _genericType = genericType;
            _event = event;
            _sequenceNumber = _sequence.getAndIncrement();
        }

        private String topic() {
            return _topic;
        }

        private Type genericType() {
            return _genericType;
        }

        private Object event() {
            return _event;
        }

        @Override
        public int compareTo(QueuedEvent other) {
            return Long.compare(_sequenceNumber, other._sequenceNumber);
        }
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private final AtomicInteger _sequence = new AtomicInteger();
        private final String _prefix;

        private NamedThreadFactory(String prefix) {
            _prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, _prefix + '-' + _sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }

    private static final class DurationStats {
        private final LongAdder _count = new LongAdder();
        private final LongAdder _total = new LongAdder();
        private final LongAccumulator _max = new LongAccumulator(Long::max, 0L);

        private void record(long duration) {
            _count.increment();
            _total.add(duration);
            _max.accumulate(duration);
        }

        private DurationSnapshot snapshot() {
            long count = _count.sum();
            long total = _total.sum();
            return new DurationSnapshot(count, count == 0L ? 0.0D : (double) total / count, _max.get());
        }
    }

    private record DurationSnapshot(long count, double mean, long max) {
    }
}
