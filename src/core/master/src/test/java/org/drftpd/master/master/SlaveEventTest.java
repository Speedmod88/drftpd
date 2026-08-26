/*
 * This file is part of DrFTPD, Distributed FTP Daemon.
 *
 * DrFTPD is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */
package org.drftpd.master.master;

import org.drftpd.common.slave.DiskStatus;
import org.drftpd.master.GlobalContext;
import org.drftpd.master.event.SlaveEvent;
import org.drftpd.master.slavemanagement.RemoteSlave;
import org.drftpd.master.slavemanagement.SlaveStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

public class SlaveEventTest {

    @Test
    public void testSlaveAnnouncementsUseDedicatedEventService() {
        assertNotSame(GlobalContext.getEventServiceSiteBotPriority(),
                GlobalContext.getSiteBotSlaveEventService());
    }

    @Test
    public void testAddSlaveStatusSnapshotIsPreserved() {
        RemoteSlave slave = new RemoteSlave("snapshot");
        SlaveStatus status = new SlaveStatus(new DiskStatus(100L, 200L),
                0L, 0L, 0, 0, 0, 0);

        SlaveEvent event = new SlaveEvent("ADDSLAVE", slave, status);

        assertSame(status, event.getSlaveStatus());
    }

    @Test
    public void testStartupEventIsReplayedWhenSiteBotDeliveryResumes() {
        GlobalContext.prepareSiteBotSlaveEventDelivery(1);
        RemoteSlave slave = new RemoteSlave("startup");
        SlaveEvent event = new SlaveEvent("MSGSLAVE", "starting remerge", slave);
        List<SlaveEvent> replayed = new ArrayList<>();

        try {
            GlobalContext.publishSlaveEvent(event);
            GlobalContext.registerSiteBotSlaveEventConsumer(replayed::add);

            assertEquals(1, replayed.size());
            assertSame(event, replayed.get(0));
        } finally {
            GlobalContext.pauseSiteBotSlaveEventDelivery();
        }
    }

    @Test
    public void testStartupReplayWaitsForAllConfiguredSiteBots() {
        GlobalContext.prepareSiteBotSlaveEventDelivery(2);
        RemoteSlave slave = new RemoteSlave("startup-multiple");
        SlaveEvent event = new SlaveEvent("ADDSLAVE", slave);
        List<SlaveEvent> firstBot = new ArrayList<>();
        List<SlaveEvent> secondBot = new ArrayList<>();

        try {
            GlobalContext.publishSlaveEvent(event);
            GlobalContext.registerSiteBotSlaveEventConsumer(firstBot::add);
            assertEquals(0, firstBot.size());

            GlobalContext.registerSiteBotSlaveEventConsumer(secondBot::add);
            assertSame(event, firstBot.get(0));
            assertSame(event, secondBot.get(0));
        } finally {
            GlobalContext.pauseSiteBotSlaveEventDelivery();
        }
    }
}
