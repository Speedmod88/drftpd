/*
 * This file is part of DrFTPD, Distributed FTP Daemon.
 *
 * DrFTPD is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */
package org.drftpd.master.sitebot.announce;

import org.drftpd.master.event.SlaveEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

final class PendingSlaveEventQueue {

    private final int _capacity;
    private final ArrayDeque<SlaveEvent> _events = new ArrayDeque<>();

    PendingSlaveEventQueue(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        _capacity = capacity;
    }

    synchronized SlaveEvent add(SlaveEvent event) {
        if (isStateEvent(event)) {
            String slaveName = event.getRSlave().getName();
            Iterator<SlaveEvent> iterator = _events.descendingIterator();
            while (iterator.hasNext()) {
                SlaveEvent queued = iterator.next();
                if (!isStateEvent(queued)
                        || !queued.getRSlave().getName().equalsIgnoreCase(slaveName)) {
                    continue;
                }
                if (queued.getCommand().equals(event.getCommand())) {
                    iterator.remove();
                }
                break;
            }
        }

        SlaveEvent dropped = null;
        if (_events.size() >= _capacity) {
            dropped = _events.removeFirst();
        }
        _events.addLast(event);
        return dropped;
    }

    synchronized List<SlaveEvent> drain() {
        List<SlaveEvent> drained = new ArrayList<>(_events);
        _events.clear();
        return drained;
    }

    synchronized int size() {
        return _events.size();
    }

    synchronized boolean isEmpty() {
        return _events.isEmpty();
    }

    synchronized void clear() {
        _events.clear();
    }

    static boolean isStateEvent(SlaveEvent event) {
        return "ADDSLAVE".equals(event.getCommand()) || "DELSLAVE".equals(event.getCommand());
    }
}
