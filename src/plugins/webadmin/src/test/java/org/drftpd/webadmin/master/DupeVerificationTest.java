package org.drftpd.webadmin.master;
import org.drftpd.master.vfs.FileHandle;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class DupeVerificationTest {
    @Test void rejectsActiveFailedAndEmptyFiles() throws Exception {
        FileHandle file = mock(FileHandle.class);
        when(file.getSize()).thenReturn(100L);
        assertTrue(WebAdminDupeVerification.readable(file));
        assertNull(WebAdminDupeVerification.unreadableReason(file));
        when(file.isTransferring()).thenReturn(true);
        assertEquals("active transfer", WebAdminDupeVerification.unreadableReason(file));
        assertFalse(WebAdminDupeVerification.readable(file));
        when(file.isTransferring()).thenReturn(false);
        when(file.getXfertime()).thenReturn(-1L);
        assertEquals("VFS marks transfer unfinished (xfertime=-1)", WebAdminDupeVerification.unreadableReason(file));
        assertFalse(WebAdminDupeVerification.readable(file));
        when(file.getXfertime()).thenReturn(0L);
        when(file.getSize()).thenReturn(0L);
        assertEquals("empty file", WebAdminDupeVerification.unreadableReason(file));
        assertFalse(WebAdminDupeVerification.readable(file));
        verify(file, never()).getCheckSumFromSlave();
    }
}
