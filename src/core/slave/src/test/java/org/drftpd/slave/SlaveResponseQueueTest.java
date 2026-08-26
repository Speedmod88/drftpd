/*
 * This file is part of DrFTPD, Distributed FTP Daemon.
 *
 * DrFTPD is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */
package org.drftpd.slave;

import org.drftpd.common.network.AsyncResponse;
import org.drftpd.common.slave.DiskStatus;
import org.drftpd.common.slave.LightRemoteInode;
import org.drftpd.common.slave.TransferIndex;
import org.drftpd.common.slave.TransferStatus;
import org.drftpd.slave.network.AsyncResponseDiskStatus;
import org.drftpd.slave.network.AsyncResponseRemerge;
import org.drftpd.slave.network.AsyncResponseTransferStatus;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.concurrent.PriorityBlockingQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

public class SlaveResponseQueueTest {

    @Test
    public void testChecksumCommandsDoNotShareDeleteRenameExecutor() {
        assertEquals(Slave.CommandExecutorType.CHECKSUM,
                Slave.getCommandExecutorType("checksum"));
        assertEquals(Slave.CommandExecutorType.FILESYSTEM,
                Slave.getCommandExecutorType("delete"));
        assertEquals(Slave.CommandExecutorType.FILESYSTEM,
                Slave.getCommandExecutorType("deletezero"));
        assertEquals(Slave.CommandExecutorType.FILESYSTEM,
                Slave.getCommandExecutorType("rename"));
    }

    @Test
    public void testControlResponsesOvertakeStatusAndRemergeResponses() {
        AsyncResponseRemerge remerge = new AsyncResponseRemerge(
                "/section", Collections.<LightRemoteInode>emptyList(), 0L);
        AsyncResponseTransferStatus status = new AsyncResponseTransferStatus(
                new TransferStatus(1L, 1L, 0L, false, new TransferIndex(1)));
        AsyncResponseDiskStatus disk = new AsyncResponseDiskStatus(new DiskStatus(1L, 2L));
        AsyncResponse control = new AsyncResponse("control");

        PriorityBlockingQueue<Slave.QueuedResponse> queue = new PriorityBlockingQueue<>();
        queue.add(queued(remerge, 0L));
        queue.add(queued(disk, 1L));
        queue.add(queued(status, 2L));
        queue.add(queued(control, 3L));

        assertSame(control, queue.remove().response());
        assertSame(status, queue.remove().response());
        assertSame(disk, queue.remove().response());
        assertSame(remerge, queue.remove().response());
    }

    @Test
    public void testResponsesWithEqualPriorityRemainFifo() {
        AsyncResponse first = new AsyncResponse("first");
        AsyncResponse second = new AsyncResponse("second");

        PriorityBlockingQueue<Slave.QueuedResponse> queue = new PriorityBlockingQueue<>();
        queue.add(queued(second, 2L));
        queue.add(queued(first, 1L));

        assertSame(first, queue.remove().response());
        assertSame(second, queue.remove().response());
    }

    private static Slave.QueuedResponse queued(AsyncResponse response, long sequence) {
        int priority = Slave.getResponsePriority(response);
        return new Slave.QueuedResponse(response, priority, sequence,
                response instanceof AsyncResponseRemerge);
    }
}
