package org.drftpd.webadmin.master;

import java.io.FileNotFoundException;
import org.drftpd.master.vfs.DirectoryHandle;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebAdminDupeSizeTest {
    @Test void readsVfsSizeWithoutSlaveRequests() throws Exception {
        var directory = mock(DirectoryHandle.class);
        when(directory.getSize()).thenReturn(5368709120L, 0L);
        assertEquals(5368709120L, WebAdminDupe.releaseSize(directory));
        assertEquals(0L, WebAdminDupe.releaseSize(directory));
        verify(directory, times(2)).getSize();
        verifyNoMoreInteractions(directory);
    }

    @Test void unavailableSizesRemainUnknown() throws Exception {
        var directory = mock(DirectoryHandle.class);
        when(directory.getSize()).thenReturn(-1L).thenThrow(new FileNotFoundException());
        assertNull(WebAdminDupe.releaseSize(directory));
        assertNull(WebAdminDupe.releaseSize(directory));
    }
}
