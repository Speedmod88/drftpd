/*
 * This file is part of DrFTPD, Distributed FTP Daemon.
 *
 * DrFTPD is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */
package org.drftpd.master.slavemanagement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RemoteSlaveRemergeModeTest {

    @Test
    public void testOnlyInstantModeDefersPersistentOperationsUntilAfterRemerge() {
        assertTrue(RemoteSlave.shouldDeferQueuedOperationsUntilAfterRemerge("instant"));
        assertTrue(RemoteSlave.shouldDeferQueuedOperationsUntilAfterRemerge("INSTANT"));
        assertFalse(RemoteSlave.shouldDeferQueuedOperationsUntilAfterRemerge("off"));
        assertFalse(RemoteSlave.shouldDeferQueuedOperationsUntilAfterRemerge("connect"));
        assertFalse(RemoteSlave.shouldDeferQueuedOperationsUntilAfterRemerge(null));
    }
}
