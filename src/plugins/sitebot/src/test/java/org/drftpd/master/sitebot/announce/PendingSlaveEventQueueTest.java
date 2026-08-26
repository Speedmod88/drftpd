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
import org.drftpd.master.slavemanagement.RemoteSlave;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

public class PendingSlaveEventQueueTest {

    @Test
    public void testStateTransitionsArePreserved() {
        PendingSlaveEventQueue queue = new PendingSlaveEventQueue(4);
        RemoteSlave slave = new RemoteSlave("EvoFAST");

        assertNull(queue.add(new SlaveEvent("ADDSLAVE", slave)));
        assertNull(queue.add(new SlaveEvent("DELSLAVE", "offline", slave)));

        List<SlaveEvent> events = queue.drain();
        assertEquals(2, events.size());
        assertEquals("ADDSLAVE", events.get(0).getCommand());
        assertEquals("DELSLAVE", events.get(1).getCommand());
    }

    @Test
    public void testDuplicateStateIsReplaced() {
        PendingSlaveEventQueue queue = new PendingSlaveEventQueue(4);
        RemoteSlave slave = new RemoteSlave("EvoFAST");
        SlaveEvent first = new SlaveEvent("ADDSLAVE", slave);
        SlaveEvent latest = new SlaveEvent("ADDSLAVE", slave);

        queue.add(first);
        queue.add(latest);

        List<SlaveEvent> events = queue.drain();
        assertEquals(1, events.size());
        assertSame(latest, events.get(0));
    }

    @Test
    public void testMessageEventsKeepFifoOrder() {
        PendingSlaveEventQueue queue = new PendingSlaveEventQueue(4);
        RemoteSlave slave = new RemoteSlave("EvoFAST");
        SlaveEvent first = new SlaveEvent("MSGSLAVE", "first", slave);
        SlaveEvent second = new SlaveEvent("MSGSLAVE", "second", slave);

        queue.add(first);
        queue.add(second);

        List<SlaveEvent> events = queue.drain();
        assertSame(first, events.get(0));
        assertSame(second, events.get(1));
    }

    @Test
    public void testCapacityDropsOldestEvent() {
        PendingSlaveEventQueue queue = new PendingSlaveEventQueue(2);
        RemoteSlave slave = new RemoteSlave("EvoFAST");
        SlaveEvent first = new SlaveEvent("MSGSLAVE", "first", slave);
        SlaveEvent second = new SlaveEvent("MSGSLAVE", "second", slave);
        SlaveEvent third = new SlaveEvent("MSGSLAVE", "third", slave);

        queue.add(first);
        queue.add(second);
        assertSame(first, queue.add(third));

        List<SlaveEvent> events = queue.drain();
        assertSame(second, events.get(0));
        assertSame(third, events.get(1));
    }
}
