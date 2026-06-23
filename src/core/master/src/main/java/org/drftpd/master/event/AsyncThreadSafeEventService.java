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

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * @author djb61
 * @version $Id$
 */
public final class AsyncThreadSafeEventService extends ThreadSafeEventService {

	private static final Logger logger = LogManager.getLogger(AsyncThreadSafeEventService.class);

    private final LinkedBlockingQueue<QueuedAsyncEvent> _eventQueue = new LinkedBlockingQueue<>();

	private EventHandler eventHandler = null;

    public AsyncThreadSafeEventService() {
        super();
        eventHandler = new EventHandler();
        new Thread(eventHandler).start();
    }

    public void publishAsync(Object event) {
        _eventQueue.add(new QueuedAsyncEvent(event));
    }

    public void publishAsync(Type genericType, Object event) {
        _eventQueue.add(new QueuedAsyncEvent(genericType, event));
    }

    public void publishAsync(String topicName, Object eventObj) {
        _eventQueue.add(new QueuedAsyncEvent(topicName, eventObj));
    }

    public int getQueueSize() {
        return _eventQueue.size();
    }

    public String getQueueSummary() {

	StringBuilder builder = new StringBuilder();

	Map<String, Map<String, Integer>> countEventPerSlave = new HashMap<>();
	Map<String, Integer> countEventNotFromSlave = new HashMap<>();

	synchronized (this) {

		logger.info("FIFO PROCESSING STATS - EVENT QUEUE SIZE={})", _eventQueue.size());
		Iterator<QueuedAsyncEvent> it = _eventQueue.iterator();
		while (it.hasNext()) {

			QueuedAsyncEvent event = it.next();
			String eventName = event.getEvent().getClass().getName();
			String sourceThreadName = event.getSourceThreadName();
			if (sourceThreadName.startsWith("RemoteSlave - ")) {

				String slaveName = sourceThreadName.substring(14);

				Map<String, Integer> eventCountMap = countEventPerSlave.get(slaveName);
				if (eventCountMap == null) {
					eventCountMap = new HashMap<>();
					countEventPerSlave.put(slaveName, eventCountMap);
				}
				Integer countEvent = eventCountMap.get(eventName);
				if (countEvent == null) {
					countEvent = Integer.valueOf(1);
					eventCountMap.put(eventName, countEvent);
				}
				else {
					int count = countEvent.intValue();
					countEvent = Integer.valueOf(count++);
					eventCountMap.put(eventName, countEvent);
				}
			}
			else {
				Integer countEvent = countEventNotFromSlave.get(eventName);
				if (countEvent == null) {
					countEvent = Integer.valueOf(1);
					countEventNotFromSlave.put(eventName, countEvent);
				}
				else {
					int count = countEvent.intValue();
					count = count + 1;
					countEvent = Integer.valueOf(count);
					countEventNotFromSlave.put(eventName, countEvent);
				}
			}

		}

		Map<String, Map<String, java.util.List<Integer>>> durationEventPerSlave = eventHandler.getDurationEventPerSlave();
		Map<String, java.util.List<Integer>> durationEventNotFromSlave = eventHandler.getDurationEventNotPerSlave();


		java.util.HashSet<String> allSlaves = new java.util.HashSet<>();
		allSlaves.addAll(countEventPerSlave.keySet());
		allSlaves.addAll(durationEventPerSlave.keySet());

		for (String slave : allSlaves) {

			Map<String, Integer> eventInQueue = countEventPerSlave.get(slave);
			if (eventInQueue != null && !eventInQueue.isEmpty()) {
				//logger.info("FIFO PROCESSING STATS - SLAVE={})", slave);
				//builder.append("\n").append("\n").append("FIFO PROCESSING STATS - SLAVE=").append(slave).append("\n");
				Iterator<Entry<String, Integer>> it44 = eventInQueue.entrySet().iterator();
				while (it44.hasNext()) {
					Entry<String, Integer> entry = it44.next();
					logger.info("FIFO PROCESSING STATS - SLAVE={} EVENT QUEUED={} TOTAL={})", slave, entry.getKey(), entry.getValue().intValue());
					builder.append("FIFO PROCESSING STATS - SLAVE=").append(slave).append(" EVENT QUEUED=").append(entry.getKey()).append(" TOTAL=").append(entry.getValue().intValue()).append("\n");
				}
			}
			else {
				logger.info("FIFO PROCESSING STATS - NO EVENTS QUEUED FOR SLAVE={})", slave);
				builder.append("FIFO PROCESSING STATS - NO EVENTS QUEUED FOR SLAVE=").append(slave).append("\n");
			}

			long allSlaveCount = 0;
			Map<String, java.util.List<Integer>> eventProcessed = durationEventPerSlave.get(slave);
			if (eventProcessed != null && !eventProcessed.isEmpty()) {
				Iterator<Entry<String, java.util.List<Integer>>> it2 = eventProcessed.entrySet().iterator();
				while (it2.hasNext()) {
					Entry<String, java.util.List<Integer>> entry = it2.next();
					long totalDuration = 0;
					for (Integer duration : entry.getValue()) {
						totalDuration += duration.intValue();
					}
					float meanDuration = totalDuration / entry.getValue().size();

					logger.info("FIFO PROCESSING STATS - SLAVE={} EVENT PROCESSED={} TOTAL={} MEAN DURATION={})", slave, entry.getKey(), entry.getValue().size(), meanDuration);
					builder.append("FIFO PROCESSING STATS - SLAVE=").append(slave).append(" EVENT PROCESSED=").append(entry.getKey()).append(" TOTAL=").append(entry.getValue().size()).append(" MEAN DURATION=").append(meanDuration).append("\n");
					allSlaveCount += entry.getValue().size();
				}

				logger.info("FIFO PROCESSING STATS - SLAVE={} EVENTS PROCESSED TOTAL={})", slave, allSlaveCount);
				builder.append("FIFO PROCESSING STATS - SLAVE=").append(slave).append(" EVENTS PROCESSED TOTAL=").append(allSlaveCount).append("\n");
			}
			else {
				logger.info("FIFO PROCESSING STATS - SLAVE={} NO EVENTS PROCESSED)", slave);
				builder.append("FIFO PROCESSING STATS - SLAVE=").append(slave).append(" NO EVENTS PROCESSED").append("\n").append("\n");
			}
		}

		if (!countEventNotFromSlave.isEmpty()) {
			long allCount = 0;
				logger.info("FIFO PROCESSING STATS - NOT ORIGINATING FROM SLAVES TOTAL EVENT CATEGORY={}", countEventNotFromSlave.size());
				builder.append("FIFO PROCESSING STATS - NOT ORIGINATING FROM SLAVES").append("\n");
				Iterator<Entry<String, Integer>> it33 = countEventNotFromSlave.entrySet().iterator();
				while (it33.hasNext()) {
					Entry<String, Integer> entry = it33.next();
					logger.info("FIFO PROCESSING STATS - EVENT QUEUED={} TOTAL={})", entry.getKey(), entry.getValue().intValue());
					builder.append("FIFO PROCESSING STATS - EVENT QUEUED=").append(entry.getKey()).append(" TOTAL=").append(entry.getValue().intValue()).append("\n");
					allCount += entry.getValue().intValue();
				}
				logger.info("FIFO PROCESSING STATS - EVENT QUEUED TOTAL={})", allCount);
				builder.append("FIFO PROCESSING STATS - EVENT QUEUED TOTAL=").append(allCount).append("\n").append("\n");
		}
		else {
			logger.info("FIFO PROCESSING STATS - NO EVENTS NOT ORIGINATING FROMS SLAVES IN QUEUE");
			builder.append("FIFO PROCESSING STATS - NO EVENTS NOT ORIGINATING FROMS SLAVES IN QUEUE").append("\n").append("\n");
		}

		if (!durationEventNotFromSlave.isEmpty()) {
				long allCount = 0;
				Iterator<Entry<String, java.util.List<Integer>>> it2 = durationEventNotFromSlave.entrySet().iterator();
				while (it2.hasNext()) {
					Entry<String, java.util.List<Integer>> entry = it2.next();
					long totalDuration = 0;
					for (Integer duration : entry.getValue()) {
						totalDuration += duration.intValue();
					}
					float meanDuration = totalDuration / entry.getValue().size();
					logger.info("FIFO PROCESSING STATS - EVENT PROCESSED={} TOTAL={} MEAN DURATION={})", entry.getKey(), entry.getValue().size(), meanDuration);
					builder.append("FIFO PROCESSING STATS - EVENT PROCESSED=").append(entry.getKey()).append(" TOTAL=").append(entry.getValue().size()).append(" MEAN DURATION=").append(meanDuration).append("\n");
					allCount += entry.getValue().size();
				}

				logger.info("FIFO PROCESSING STATS - EVENT PROCESSED TOTAL={})", allCount);
				builder.append("FIFO PROCESSING STATS - EVENT PROCESSED TOTAL=").append(allCount).append("\n").append("\n");
		}
		else {
				logger.info("FIFO PROCESSING STATS - NO EVENTS PROCESSED");
				builder.append("FIFO PROCESSING STATS - NO EVENTS PROCESSED").append("\n").append("\n");
		}

		if (eventHandler.currentSourceThreadName != null) {
			logger.info("FIFO PROCESSING STATS - EVENT BEING PROCESSED - THREAD={} EVENT={} SINCE={})", eventHandler.currentSourceThreadName, eventHandler.currentEventName, System.currentTimeMillis() - eventHandler.lastTake);
			builder.append("FIFO PROCESSING STATS - EVENT BEING PROCESSED - THREAD=").append(eventHandler.currentSourceThreadName).append(" EVENT=").append(eventHandler.currentEventName).append(" SINCE=").append(System.currentTimeMillis() - eventHandler.lastTake).append("\n").append("\n");
		}
		else {
				logger.info("FIFO PROCESSING STATS - NO EVENT BEING PROCESSED");
				builder.append("FIFO PROCESSING STATS - NO EVENT BEING PROCESSED").append("\n").append("\n");
		}

	}
        return builder.toString();
    }

    private static class QueuedAsyncEvent {

        private final Object _event;
        private String _topic;
        private Type _genericType;
        private String sourceThreadName = Thread.currentThread().getName();
        private long time = System.currentTimeMillis();

        private QueuedAsyncEvent(Object event) {
            _event = event;
        }

        private QueuedAsyncEvent(String topic, Object event) {
            _topic = topic;
            _event = event;
        }

        private QueuedAsyncEvent(Type genericType, Object event) {
            _genericType = genericType;
            _event = event;
        }

        private Object getEvent() {
            return _event;
        }

        private String getTopic() {
            return _topic;
        }

        private Type getGenericType() {
            return _genericType;
        }

        private String getSourceThreadName() {
		return sourceThreadName;
        }
    }

    private class EventHandler implements Runnable {

	private final Map<String, Map<String, java.util.List<Integer>>> durationEventPerSlave = new HashMap<>();
	private final Map<String, java.util.List<Integer>> durationEventNotFromSlave = new HashMap<>();
	private long lastTake = 0;
	private String currentEventName = null;
	private String currentSourceThreadName = null;

	private Map<String, Map<String, java.util.List<Integer>>> getDurationEventPerSlave() {
		return durationEventPerSlave;
	}

	private Map<String, java.util.List<Integer>> getDurationEventNotPerSlave() {
		return durationEventNotFromSlave;
	}

        public void run() {
            //noinspection InfiniteLoopStatement
            while (true) {
		QueuedAsyncEvent queuedEvent = null;
                try {

			synchronized (this) {

				lastTake = System.currentTimeMillis();
	                    queuedEvent = _eventQueue.take();


	                    if (queuedEvent.getEvent() instanceof SlaveEvent) {
				currentEventName = ((SlaveEvent)queuedEvent.getEvent()).getCommand();
	                    }
	                    else {
				currentEventName = queuedEvent.getEvent().getClass().getName();
	                    }

	                    currentSourceThreadName = queuedEvent.getSourceThreadName();
	                    if (queuedEvent.getTopic() != null) {
	                        publish(queuedEvent.getTopic(), queuedEvent.getEvent());
	                    } else if (queuedEvent.getGenericType() != null) {
	                        publish(queuedEvent.getGenericType(), queuedEvent.getEvent());
	                    } else {
	                        publish(queuedEvent.getEvent());
	                    }
	                    long end = System.currentTimeMillis();


					if (currentSourceThreadName.startsWith("RemoteSlave - ")) {
						String slaveName = currentSourceThreadName.substring(14);

						Map<String, java.util.List<Integer>> eventDurationMap = durationEventPerSlave.get(slaveName);
						if (eventDurationMap == null) {
							eventDurationMap = new HashMap<>();
							durationEventPerSlave.put(slaveName, eventDurationMap);
						}
						java.util.List<Integer> durationEvent = eventDurationMap.get(currentEventName);
						if (durationEvent == null) {
							durationEvent = new java.util.ArrayList<>();
							durationEvent.add(Long.valueOf(end - lastTake).intValue());
							eventDurationMap.put(currentEventName, durationEvent);
						}
						else {
							durationEvent.add(Long.valueOf(end - lastTake).intValue());
						}
					}
					else {
						java.util.List<Integer> durationEvent = durationEventNotFromSlave.get(currentEventName);
						if (durationEvent == null) {
							durationEvent = new java.util.ArrayList<>();
							durationEvent.add(Long.valueOf(end - lastTake).intValue());
							durationEventNotFromSlave.put(currentEventName, durationEvent);
						}
						else {
							durationEvent.add(Long.valueOf(end - lastTake).intValue());
						}
					}

			}
                } catch (InterruptedException e) {
                    // Do nothing just loop and try again
                } catch (Throwable t) {
			logger.error("FATAL ERROR EventHandler FIFO. ({}, {}, {})", queuedEvent.getTopic(), queuedEvent.getGenericType(), queuedEvent.getEvent(), t);
                }
            }
        }
    }
}
