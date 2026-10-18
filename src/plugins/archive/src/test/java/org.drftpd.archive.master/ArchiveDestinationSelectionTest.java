package org.drftpd.archive.master;

import org.drftpd.archive.master.archivetypes.*;
import org.drftpd.common.slave.DiskStatus;
import org.drftpd.master.exceptions.SlaveUnavailableException;
import org.drftpd.master.slavemanagement.*;
import org.drftpd.master.vfs.DirectoryHandle;
import org.drftpd.slave.exceptions.ObjectNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ArchiveDestinationSelectionTest {
    private final Map<String, TestSlave> slaves = new HashMap<>();
    private Properties config;

    @BeforeEach void setup() {
        slaves.put("Evo", new TestSlave("Evo", 100));
        slaves.put("EvoI", new TestSlave("EvoI", 200));
        slaves.put("EvoFAST", new TestSlave("EvoFAST", 900));
        slaves.put("Other", new TestSlave("Other", 1000));
        new Context(new Manager(slaves));
        config = new Properties();
        config.setProperty("1.numofslaves", "1");
        config.setProperty("1.slavename.1", "Evo");
        config.setProperty("1.slavename.2", "EvoI");
        config.setProperty("1.offofslave.1", "  EvoFAST  ");
    }

    private ArchiveType type(boolean off) {
        var archive = new MoveReleaseOffMultipleSlavesToSpecificSlavesTest.TestArchive();
        var section = new MoveReleaseOffMultipleSlavesToSpecificSlavesTest.Section(new DirectoryHandle("/movies"));
        return off ? new MoveReleaseOffSlavesToMostFreeSlaves(archive, section, config, 1)
                : new MoveReleaseToMostFreeSlaves(archive, section, config, 1);
    }

    @Test void ranksOnlyConfiguredDestinationsAndTrimsSource() {
        assertEquals(Set.of(slaves.get("EvoI")), type(true).findDestinationSlaves());
        assertEquals(Set.of(slaves.get("EvoI")), type(false).findDestinationSlaves());
        config.remove("1.slavename.2");
        assertEquals(Set.of(slaves.get("Evo")), type(true).findDestinationSlaves());
    }

    @Test void preservesEqualSpaceDestinations() {
        slaves.get("EvoI").free = 100;
        config.setProperty("1.numofslaves", "2");
        assertEquals(Set.of(slaves.get("Evo"), slaves.get("EvoI")), type(true).findDestinationSlaves());
        config.setProperty("1.numofslaves", "1");
        assertEquals(Set.of(slaves.get("Evo")), type(true).findDestinationSlaves());
    }

    @Test void skipsOfflineAndFullDestinations() {
        slaves.get("EvoI").online = false;
        assertEquals(Set.of(slaves.get("Evo")), type(true).findDestinationSlaves());
        slaves.get("EvoI").online = true;
        slaves.get("EvoI").full = true;
        assertEquals(Set.of(slaves.get("Evo")), type(true).findDestinationSlaves());
        slaves.get("Evo").free = 0;
        assertTrue(type(true).findDestinationSlaves().isEmpty());
    }

    @Test void doesNotEnqueueWithoutEnoughDestinations() {
        ArchiveType archive = type(true);
        assertThrows(IllegalStateException.class, () -> archive.send(Set.of()));
        config.setProperty("1.numofslaves", "2");
        ArchiveType twoCopies = type(true);
        assertThrows(IllegalStateException.class, () -> twoCopies.send(Set.of(slaves.get("Evo"))));
    }

    @Test void rechecksAvailabilityAndSpaceOnNextSelection() {
        ArchiveType archive = type(true);
        assertEquals(Set.of(slaves.get("EvoI")), archive.findDestinationSlaves());
        slaves.get("Evo").free = 300;
        assertEquals(Set.of(slaves.get("Evo")), archive.findDestinationSlaves());
    }

    @Test void unknownSourceDoesNotHideValidSource() {
        config.setProperty("1.offofslave.1", "missing");
        config.setProperty("1.offofslave.2", " EvoFAST ");
        assertEquals(Set.of(slaves.get("EvoI")), type(true).findDestinationSlaves());
        config.remove("1.offofslave.2");
        assertThrows(NullPointerException.class, () -> type(true));
    }

    private static class Context extends MoveReleaseOffMultipleSlavesToSpecificSlavesTest.GC {
        Context(SlaveManager manager) { _gctx = this; _slaveManager = manager; }
    }
    private static class Manager extends SlaveManager {
        final Map<String, TestSlave> slaves;
        Manager(Map<String, TestSlave> slaves) { super("test"); this.slaves = slaves; }
        @Override public RemoteSlave getRemoteSlave(String name) throws ObjectNotFoundException {
            if (!slaves.containsKey(name)) throw new ObjectNotFoundException();
            return slaves.get(name);
        }
    }
    private static class TestSlave extends RemoteSlave {
        long free;
        boolean online = true;
        boolean full;
        TestSlave(String name, long free) { super(name); this.free = free; }
        @Override public boolean isUploadBlockedByCapacity() { return full; }
        @Override public SlaveStatus getSlaveStatusAvailable() throws SlaveUnavailableException {
            if (!online) throw new SlaveUnavailableException("offline");
            return new SlaveStatus(new DiskStatus(free, 2000), 0, 0, 0, 0, 0, 0);
        }
    }
}
