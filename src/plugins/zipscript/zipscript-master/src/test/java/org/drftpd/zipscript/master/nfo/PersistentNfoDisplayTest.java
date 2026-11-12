package org.drftpd.zipscript.master.nfo;

import org.drftpd.master.slavemanagement.RemoteSlave;
import org.drftpd.master.vfs.FileHandle;
import org.drftpd.master.vfs.event.ImmutableInodeHandle;
import org.drftpd.master.vfs.event.VirtualFileSystemInodeDeletedEvent;
import org.drftpd.master.vfs.event.VirtualFileSystemRenameEvent;
import org.drftpd.slave.NfoReader;
import org.drftpd.slave.network.AsyncResponseLog;
import org.drftpd.slave.network.AsyncResponseMaxPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PersistentNfoDisplayTest {
    @TempDir Path directory;

    private NfoDisplay display(int waitMillis) throws Exception {
        NfoDisplay display = new NfoDisplay();
        var settings = NfoDisplay.class.getDeclaredField("settings"); settings.setAccessible(true);
        settings.set(display, new NfoDisplay.Settings(true, 200, 10, 3600000));
        var storage = NfoDisplay.class.getDeclaredField("storage"); storage.setAccessible(true);
        storage.set(display, new NfoDisplay.Storage(true, directory, waitMillis, false, 8, 2000));
        return display;
    }

    private RemoteSlave slave() throws Exception {
        RemoteSlave slave = mock(RemoteSlave.class);
        when(slave.getName()).thenReturn("test"); when(slave.isAvailable()).thenReturn(true);
        when(slave.getRenameQueue()).thenReturn(new ConcurrentLinkedDeque<>());
        when(slave.fetchIndex()).thenReturn("probe", "text", "new");
        when(slave.fetchResponseWithoutDisconnect("probe", 5000)).thenReturn(new AsyncResponseMaxPath("probe", NfoReader.CAPABILITY));
        when(slave.fetchResponseWithoutDisconnect("text", 5000)).thenReturn(new AsyncResponseLog("text", List.of(), "info", null));
        when(slave.fetchResponseWithoutDisconnect("new", 5000)).thenReturn(new AsyncResponseLog("new", List.of(), "new!", null));
        return slave;
    }

    private FileHandle file(RemoteSlave slave) throws Exception {
        FileHandle file = mock(FileHandle.class);
        when(file.getName()).thenReturn("release.nfo");
        when(file.getPath()).thenReturn("/SECTION/Release-GRP/release.nfo");
        when(file.getSize()).thenReturn(4L); when(file.creationTime()).thenReturn(1L);
        when(file.lastModified()).thenReturn(2L); when(file.getSlaves()).thenReturn(Set.of(slave));
        return file;
    }

    private void fence(NfoDisplay display) throws Exception {
        var field = NfoDisplay.class.getDeclaredField("worker"); field.setAccessible(true);
        ((ThreadPoolExecutor) field.get(display)).submit(() -> {}).get(10, TimeUnit.SECONDS);
    }

    @Test void firstCwdReadsAndPersistsTextAndRestartNeedsNoSlaveRequest() throws Exception {
        RemoteSlave slave = slave(); FileHandle file = file(slave);
        NfoDisplay first = display(2000);
        try { assertEquals(List.of("info"), first.linesForCwd(file)); }
        finally { first.stopPlugin("test"); }
        when(slave.isAvailable()).thenReturn(false);
        NfoDisplay second = display(2000);
        try { assertEquals(List.of("info"), second.linesForCwd(file)); }
        finally { second.stopPlugin("test"); }
        verify(slave, times(2)).sendCommand(any());
        verify(file, never()).getCheckSumFromSlave();
    }

    @Test void changedNfoReplacesStoredVersion() throws Exception {
        RemoteSlave slave = slave(); FileHandle file = file(slave);
        NfoDisplay display = display(2000);
        try {
            assertEquals(List.of("info"), display.linesForCwd(file));
            when(file.lastModified()).thenReturn(3L);
            assertEquals(List.of("new!"), display.linesForCwd(file));
        } finally { display.stopPlugin("test"); }
        try (var store = new NfoStore(directory)) {
            assertNull(store.get(new NfoDisplay.Key(file.getPath(), 1, 4, 2, 0)));
            assertEquals("new!", store.get(new NfoDisplay.Key(file.getPath(), 1, 4, 3, 0)));
        }
    }

    @Test void releaseDeletionPurgesMemoryAndDurableDescendants() throws Exception {
        RemoteSlave slave = slave(); FileHandle file = file(slave);
        NfoDisplay display = display(2000);
        try {
            assertEquals(List.of("info"), display.linesForCwd(file));
            var deleted = mock(VirtualFileSystemInodeDeletedEvent.class);
            var inode = mock(ImmutableInodeHandle.class);
            when(deleted.getImmutableInode()).thenReturn(inode);
            when(inode.isDirectory()).thenReturn(true); when(inode.getPath()).thenReturn("/SECTION/Release-GRP");
            display.deleted(deleted);
            when(slave.isAvailable()).thenReturn(false);
            assertTrue(display.linesForCwd(file).isEmpty());
        } finally { display.stopPlugin("test"); }
        try (var store = new NfoStore(directory)) {
            assertNull(store.get(new NfoDisplay.Key(file.getPath(), 1, 4, 2, 0)));
        }
    }

    @Test void deletionDuringFetchCannotReinsertText() throws Exception {
        RemoteSlave slave = slave(); FileHandle file = file(slave);
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(slave.fetchResponseWithoutDisconnect("text", 5000)).thenAnswer(call -> {
            started.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS));
            return new AsyncResponseLog("text", List.of(), "info", null);
        });
        NfoDisplay display = display(100);
        try {
            display.lines(file); assertTrue(started.await(5, TimeUnit.SECONDS));
            display.invalidate("/SECTION/Release-GRP");
            release.countDown(); fence(display);
        } finally { release.countDown(); display.stopPlugin("test"); }
        try (var store = new NfoStore(directory)) {
            assertNull(store.get(new NfoDisplay.Key(file.getPath(), 1, 4, 2, 0)));
        }
    }

    @Test void cwdDeadlineReturnsWithoutWaitingForSlowSlave() throws Exception {
        RemoteSlave slave = slave(); FileHandle file = file(slave);
        var release = new CountDownLatch(1);
        when(slave.fetchResponseWithoutDisconnect("text", 5000)).thenAnswer(call -> {
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return new AsyncResponseLog("text", List.of(), "info", null);
        });
        NfoDisplay display = display(75);
        try {
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(1), () -> assertTrue(display.linesForCwd(file).isEmpty()));
            release.countDown(); fence(display);
            assertEquals(List.of("info"), display.linesForCwd(file));
        } finally { release.countDown(); display.stopPlugin("test"); }
    }

    @Test void renameInvalidatesBothOldAndDestinationTrees() throws Exception {
        RemoteSlave slave = slave(); FileHandle file = file(slave);
        NfoDisplay display = display(2000);
        try {
            assertEquals(List.of("info"), display.linesForCwd(file));
            var event = mock(VirtualFileSystemRenameEvent.class);
            var source = mock(ImmutableInodeHandle.class); var destination = mock(ImmutableInodeHandle.class);
            when(event.getSource()).thenReturn(source); when(event.getImmutableInode()).thenReturn(destination);
            when(source.getPath()).thenReturn("/SECTION/Release-GRP");
            when(destination.getPath()).thenReturn("/OTHER/Release-GRP"); when(destination.isDirectory()).thenReturn(true);
            display.renamed(event); fence(display);
        } finally { display.stopPlugin("test"); }
        try (var store = new NfoStore(directory)) {
            assertNull(store.get(new NfoDisplay.Key(file.getPath(), 1, 4, 2, 0)));
        }
    }
}
