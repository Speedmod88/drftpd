package org.drftpd.master.slavemanagement;

import org.drftpd.common.slave.DiskStatus;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RemoteSlaveCapacityTest {
    private static class TestSlave extends RemoteSlave {
        final List<String> announcements = new ArrayList<>();

        TestSlave() {
            super("test");
            setAvailable(true);
        }

        @Override
        public boolean isOnline() { return true; }

        @Override
        protected void publishDiskSpaceMessage(String message) {
            announcements.add(message);
        }
    }

    @Test
    void announcesFullAndRecoveryOncePerTransition() {
        TestSlave slave = new TestSlave();
        DiskStatus full = new DiskStatus(100, 1000, true, "root.1 free=100B min=200B");
        DiskStatus available = new DiskStatus(300, 1000, false, "root.1 free=300B min=200B");
        slave.updateDiskStatus(available);
        assertTrue(slave.announcements.isEmpty());
        slave.updateDiskStatus(full);
        slave.updateDiskStatus(full);
        assertEquals(1, slave.announcements.size());
        assertTrue(slave.getDiskSpaceWarning().contains("FULL"));
        slave.updateDiskStatus(available);
        slave.updateDiskStatus(available);
        assertEquals(2, slave.announcements.size());
        assertTrue(slave.announcements.get(1).contains("SPACE AVAILABLE"));
        assertNull(slave.getDiskSpaceWarning());
    }

    @Test
    void fullOnConnectIsReportedButLegacyStatusIsNotInvented() {
        TestSlave slave = new TestSlave();
        slave.updateDiskStatus(new DiskStatus(0, 1000));
        assertTrue(slave.announcements.isEmpty());
        assertNull(slave.getDiskSpaceWarning());
        slave.setAvailable(false);
        DiskStatus full = new DiskStatus(100, 1000, true, "root.1 free=100B min=200B");
        slave.updateDiskStatus(full);
        assertTrue(slave.announcements.isEmpty());
        slave.setAvailable(true);
        slave.updateDiskStatus(full);
        assertEquals(1, slave.announcements.size());
    }

    @Test
    void legacyWrappedPortExhaustionIsRecoverableButUnknownBugsAreNotHidden() {
        Exception wrapped = new Exception("Unable to invoke handler",
                new InvocationTargetException(new RuntimeException("PortRange exhausted")));
        assertNotNull(RemoteSlave.recoverableCommandFailure(wrapped));
        IOException failure = new IOException("No space left on device");
        assertSame(failure, RemoteSlave.recoverableCommandFailure(failure));
        assertNull(RemoteSlave.recoverableCommandFailure(new NullPointerException()));
    }
}
