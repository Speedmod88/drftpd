package org.drftpd.zipscript.master.audit;

import org.drftpd.master.vfs.FileHandle;
import org.drftpd.master.slavemanagement.RemoteSlave;
import org.junit.jupiter.api.Test;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuditFailureTest {
    @Test void goodCopyCannotHideCorruptCopyAndChangedFileInvalidatesRecord() throws Exception {
        FileHandle file = mock(FileHandle.class);
        RemoteSlave a = mock(RemoteSlave.class), b = mock(RemoteSlave.class);
        when(a.getName()).thenReturn("A"); when(b.getName()).thenReturn("B");
        when(file.getSlaves()).thenReturn(Set.of(a, b));
        when(file.getSize()).thenReturn(42L); when(file.lastModified()).thenReturn(123L);
        AtomicReference<AuditFailure> metadata = new AtomicReference<>();
        when(file.getPluginMetaData(AuditFailure.KEY)).thenAnswer(call -> metadata.get());
        doAnswer(call -> { metadata.set(call.getArgument(1)); return null; })
                .when(file).addPluginMetaData(eq(AuditFailure.KEY), any(AuditFailure.class));
        AuditFailure.record(file, "A", 0, true);
        AuditFailure.record(file, "B", 123, false);
        assertTrue(AuditFailure.isBad(file));
        AuditFailure.record(file, "A", 123, false);
        assertFalse(AuditFailure.isBad(file));
        AuditFailure.record(file, "A", 0, true);
        when(file.lastModified()).thenReturn(124L);
        assertFalse(AuditFailure.isBad(file));
        verify(file, never()).setCheckSum(anyLong());
        verify(file, never()).getCheckSumFromSlave();
    }
}
