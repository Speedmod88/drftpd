package org.drftpd.zipscript.master.nfo;

import org.drftpd.master.slavemanagement.RemoteSlave;
import org.drftpd.master.vfs.FileHandle;
import org.drftpd.slave.NfoReader;
import org.drftpd.slave.network.AsyncResponseLog;
import org.drftpd.slave.network.AsyncResponseMaxPath;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NfoCacheTest {
    private NfoDisplay enabled() throws Exception {
        NfoDisplay display = new NfoDisplay();
        var field = NfoDisplay.class.getDeclaredField("settings");
        field.setAccessible(true);
        field.set(display, new NfoDisplay.Settings(true, 200, 2, 3600000));
        return display;
    }
    private FileHandle file(RemoteSlave slave) throws Exception {
        FileHandle file = mock(FileHandle.class);
        when(file.getName()).thenReturn("release.nfo");
        when(file.getPath()).thenReturn("/SECTION/Release-GRP/release.nfo");
        when(file.getSize()).thenReturn(4L);
        when(file.creationTime()).thenReturn(1L);
        when(file.lastModified()).thenReturn(10L);
        when(file.getSlaves()).thenReturn(Set.of(slave));
        return file;
    }
    private RemoteSlave slave() {
        RemoteSlave slave = mock(RemoteSlave.class);
        when(slave.getName()).thenReturn("Evo");
        when(slave.isAvailable()).thenReturn(true);
        when(slave.getRenameQueue()).thenReturn(new ConcurrentLinkedDeque<>());
        return slave;
    }

    @Test void slowSlaveNeverBlocksCallerAndConcurrentMissesAreCoalesced() throws Exception {
        NfoDisplay display = enabled();
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        try {
            RemoteSlave slave = slave();
            FileHandle file = file(slave);
            when(slave.fetchIndex()).thenReturn("probe", "text", "text2");
            when(slave.fetchResponseWithoutDisconnect("probe", 5000)).thenAnswer(call -> {
                started.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS));
                return new AsyncResponseMaxPath("probe", NfoReader.CAPABILITY);
            });
            when(slave.fetchResponseWithoutDisconnect("text", 5000))
                    .thenReturn(new AsyncResponseLog("text", List.of(), "info", null));
            when(slave.fetchResponseWithoutDisconnect("text2", 5000))
                    .thenReturn(new AsyncResponseLog("text2", List.of(), "new!", null));
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertTrue(display.lines(file).isEmpty()));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertTrue(display.lines(file).isEmpty()));
            release.countDown();
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                while (display.lines(file).isEmpty()) Thread.sleep(10);
            });
            assertEquals(List.of("info"), display.lines(file));
            verify(slave, times(2)).sendCommand(any());
            when(file.lastModified()).thenReturn(20L);
            assertTrue(display.lines(file).isEmpty());
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                while (display.lines(file).isEmpty()) Thread.sleep(10);
            });
            assertEquals(List.of("new!"), display.lines(file));
            verify(file, never()).getCheckSum();
            verify(file, never()).getCheckSumFromSlave();
        } finally { release.countDown(); display.stopPlugin("test"); }
    }

    @Test void legacySlaveReceivesOnlySafeProbeAndFailuresAreCached() throws Exception {
        NfoDisplay display = enabled();
        try {
            RemoteSlave slave = slave();
            FileHandle file = file(slave);
            when(slave.fetchIndex()).thenReturn("probe");
            CountDownLatch replied = new CountDownLatch(1);
            when(slave.fetchResponseWithoutDisconnect("probe", 5000)).thenAnswer(call -> {
                replied.countDown(); return new AsyncResponseMaxPath("probe", 4096);
            });
            display.lines(file);
            assertTrue(replied.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 10; i++) assertTrue(display.lines(file).isEmpty());
            verify(slave, times(1)).sendCommand(any());
        } finally { display.stopPlugin("test"); }
    }
}
