package org.drftpd.zipscript.master.nfo;

import org.drftpd.master.GlobalContext;
import org.drftpd.master.event.AsyncThreadSafeEventService;
import org.drftpd.master.indexation.AdvancedSearchParams;
import org.drftpd.master.indexation.IndexEngineInterface;
import org.drftpd.master.slavemanagement.RemoteSlave;
import org.drftpd.master.slavemanagement.SlaveStatus;
import org.drftpd.master.vfs.DirectoryHandle;
import org.drftpd.master.vfs.FileHandle;
import org.drftpd.slave.NfoReader;
import org.drftpd.slave.network.AsyncResponseLog;
import org.drftpd.slave.network.AsyncResponseMaxPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NfoPrefetchTest {
    @TempDir Path directory;

    private NfoDisplay configured() throws Exception {
        var display = new NfoDisplay();
        var settings = NfoDisplay.class.getDeclaredField("settings"); settings.setAccessible(true);
        settings.set(display, new NfoDisplay.Settings(true, 200, 10, 3600000));
        var storage = NfoDisplay.class.getDeclaredField("storage"); storage.setAccessible(true);
        storage.set(display, new NfoDisplay.Storage(true, directory, 500, true, 3, 2000));
        return display;
    }

    @Test void indexPrefetchIsBoundedAndBusySlavesReceiveNoBackgroundRead() throws Exception {
        try (var global = mockStatic(GlobalContext.class)) {
            GlobalContext context = mock(GlobalContext.class);
            global.when(GlobalContext::getGlobalContext).thenReturn(context);
            var queue = mock(AsyncThreadSafeEventService.class);
            global.when(GlobalContext::getEventServiceSlowest).thenReturn(queue);
            var index = mock(IndexEngineInterface.class);
            when(context.getIndexEngine()).thenReturn(index);
            DirectoryHandle root = mock(DirectoryHandle.class); when(context.getRoot()).thenReturn(root);
            String path = "/SECTION/Release-GRP/release.nfo";
            when(index.advancedFind(eq(root), any(), eq("NFO-prefetch"))).thenReturn(Map.of(path, "file"));
            RemoteSlave slave = mock(RemoteSlave.class);
            when(slave.getName()).thenReturn("test"); when(slave.isAvailable()).thenReturn(true);
            when(slave.getRenameQueue()).thenReturn(new ConcurrentLinkedDeque<>());
            var status = mock(SlaveStatus.class); when(slave.getSlaveStatus()).thenReturn(status);
            when(status.getTransfers()).thenReturn(1);
            try (var files = mockConstruction(FileHandle.class, (file, constructor) -> {
                when(file.getPath()).thenReturn(path); when(file.getName()).thenReturn("release.nfo");
                when(file.creationTime()).thenReturn(1L); when(file.lastModified()).thenReturn(2L);
                when(file.getSize()).thenReturn(4L); when(file.getSlaves()).thenReturn(Set.of(slave));
            })) {
                NfoDisplay display = configured();
                try {
                    var maintain = NfoDisplay.class.getDeclaredMethod("maintain"); maintain.setAccessible(true);
                    maintain.invoke(display);
                    verify(index).advancedFind(eq(root), argThat(params -> params.getLimit() == 3
                            && params.getInodeType() == AdvancedSearchParams.InodeType.FILE
                            && ".nfo".equals(params.getEndsWith())), eq("NFO-prefetch"));
                    verify(slave, never()).sendCommand(any());
                    when(status.getTransfers()).thenReturn(0);
                    when(slave.fetchIndex()).thenReturn("probe", "text");
                    when(slave.fetchResponseWithoutDisconnect("probe", 5000)).thenReturn(new AsyncResponseMaxPath("probe", NfoReader.CAPABILITY));
                    when(slave.fetchResponseWithoutDisconnect("text", 5000)).thenReturn(new AsyncResponseLog("text", java.util.List.of(), "info", null));
                    var next = NfoDisplay.class.getDeclaredField("nextPrefetch"); next.setAccessible(true); next.set(display, 0L);
                    maintain.invoke(display);
                    verify(slave, times(2)).sendCommand(any());
                } finally { display.stopPlugin("test"); }
            }
            try (var store = new NfoStore(directory)) {
                assertEquals("info", store.get(new NfoDisplay.Key(path, 1, 4, 2, 0)));
            }
        }
    }

    @Test void configurationDefaultsAndBounds() {
        var defaults = NfoDisplay.Storage.read(new java.util.Properties());
        assertTrue(defaults.enabled()); assertTrue(defaults.prefetch());
        assertEquals(500, defaults.waitMillis()); assertEquals(8, defaults.batch());
        var properties = new java.util.Properties();
        properties.setProperty("cwd.wait.millis", "2001");
        assertThrows(IllegalArgumentException.class, () -> NfoDisplay.Storage.read(properties));
        properties.clear(); properties.setProperty("store.path", " ");
        assertThrows(IllegalArgumentException.class, () -> NfoDisplay.Storage.read(properties));
    }

    @Test void deletionSubscriberUsesActualVfsDeletionBus() throws Exception {
        var method = NfoDisplay.class.getMethod("deleted",
                org.drftpd.master.vfs.event.VirtualFileSystemInodeDeletedEvent.class);
        assertEquals(GlobalContext.SERVICE_NAME_EVENT_BUS_SLOWEST,
                method.getAnnotation(org.bushe.swing.event.annotation.EventSubscriber.class).eventServiceName());
    }

    @Test void maintenancePrunesNfoDeletedWhileMasterWasOffline() throws Exception {
        var key = new NfoDisplay.Key("/SECTION/Deleted-GRP/release.nfo", 1, 4, 2, 0);
        try (var store = new NfoStore(directory)) { store.put(key, "info"); }
        NfoDisplay display = configured();
        var storage = NfoDisplay.class.getDeclaredField("storage"); storage.setAccessible(true);
        storage.set(display, new NfoDisplay.Storage(true, directory, 500, false, 3, 2000));
        try (var files = mockConstruction(FileHandle.class, (file, constructor) -> {
            when(file.creationTime()).thenThrow(new java.io.FileNotFoundException("deleted"));
        })) {
            try {
                var maintain = NfoDisplay.class.getDeclaredMethod("maintain"); maintain.setAccessible(true);
                maintain.invoke(display);
            } finally { display.stopPlugin("test"); }
        }
        try (var store = new NfoStore(directory)) { assertNull(store.get(key)); }
    }
}
