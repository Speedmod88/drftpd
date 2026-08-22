/*
 * This file is part of DrFTPD, Distributed FTP Daemon.
 *
 * DrFTPD is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */
package org.drftpd.master.slavemanagement;

import org.drftpd.common.exceptions.RemoteIOException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    public void testNonFatalResponseTimeoutKeepsSlaveOnline() throws Exception {
        RemoteSlave slave = new RemoteSlave("timeout-test");
        Socket connectedSocket = new Socket() {
            @Override
            public boolean isConnected() {
                return true;
            }

            @Override
            public boolean isClosed() {
                return false;
            }
        };
        setField(slave, "_socket", connectedSocket);
        setField(slave, "_indexWithCommands", new ConcurrentHashMap<>());

        RemoteIOException failure = assertThrows(RemoteIOException.class,
                () -> slave.fetchResponseWithoutDisconnect("4d", 1));

        assertTrue(failure.getCause() instanceof SocketTimeoutException);
        assertTrue(slave.isOnline());
    }

    private static void setField(RemoteSlave slave, String name, Object value) throws Exception {
        Field field = RemoteSlave.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(slave, value);
    }
}
