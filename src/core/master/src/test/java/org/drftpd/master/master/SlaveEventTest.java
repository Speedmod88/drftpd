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
import org.drftpd.master.event.SlaveEvent;
import org.drftpd.master.slavemanagement.RemoteSlave;
import org.drftpd.master.slavemanagement.SlaveStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;

public class SlaveEventTest {

    @Test
    public void testAddSlaveStatusSnapshotIsPreserved() {
        RemoteSlave slave = new RemoteSlave("snapshot");
        SlaveStatus status = new SlaveStatus(new DiskStatus(100L, 200L),
                0L, 0L, 0, 0, 0, 0);

        SlaveEvent event = new SlaveEvent("ADDSLAVE", slave, status);

        assertSame(status, event.getSlaveStatus());
    }
}
