package org.drftpd.zipscript.master.audit;

import org.drftpd.common.dynamicdata.KeyNotFoundException;
import org.drftpd.common.network.AsyncCommandArgument;
import org.drftpd.common.vfs.CaseInsensitiveTreeMap;
import org.drftpd.master.slavemanagement.RemoteSlave;
import org.drftpd.master.slavemanagement.SlaveStatus;
import org.drftpd.master.vfs.DirectoryHandle;
import org.drftpd.master.vfs.FileHandle;
import org.drftpd.slave.IdleChecksumScanner;
import org.drftpd.slave.network.AsyncResponseMaxPath;
import org.drftpd.zipscript.common.sfv.SFVInfo;
import org.junit.jupiter.api.Test;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IntegrityAuditTest {
    private final RemoteSlave slave = mock(RemoteSlave.class);
    private final SlaveStatus status = mock(SlaveStatus.class);
    private final IntegrityAudit audit = new IntegrityAudit();

    private IntegrityAudit.Progress idle() throws Exception {
        var config = IntegrityAudit.class.getDeclaredField("config");
        config.setAccessible(true);
        config.set(audit, IntegrityConfig.read(new Properties()));
        when(slave.isOnline()).thenReturn(true);
        when(slave.getSlaveStatus()).thenReturn(status);
        when(slave.getName()).thenReturn("Evo");
        when(slave.fetchIndex()).thenReturn("a1");
        var state = new IntegrityAudit.Progress();
        state.generation = 0; state.traffic = 0; state.quietSince = 0;
        state.cursor = new IntegrityAudit.Cursor("", Long.MAX_VALUE);
        return state;
    }

    @Test void offlineRemergingAndActiveSlavesReceiveNoAuditCommands() throws Exception {
        var state = idle();
        when(slave.isOnline()).thenReturn(false);
        audit.step(slave, state, 1000000);
        when(slave.isOnline()).thenReturn(true);
        when(slave.isRemerging()).thenReturn(true);
        audit.step(slave, state, 2000000);
        when(slave.isRemerging()).thenReturn(false);
        when(status.getTransfers()).thenReturn(1);
        audit.step(slave, state, 3000000);
        verify(slave, never()).sendCommand(any());
    }

    @Test void idleGraceRestartsWhenTrafficChanges() throws Exception {
        var state = idle();
        when(status.getBytesReceived()).thenReturn(100L);
        audit.step(slave, state, 1000000);
        audit.step(slave, state, 1200000);
        verify(slave, never()).sendCommand(any());
        assertEquals(1000000, state.quietSince);
    }

    @Test void legacySlaveOnlyReceivesSafeMaxpathProbe() throws Exception {
        var state = idle();
        when(slave.fetchResponseWithoutDisconnect("a1", 15000)).thenReturn(new AsyncResponseMaxPath("a1", 4096));
        audit.step(slave, state, 1000000);
        audit.step(slave, state, 2000000);
        assertFalse(state.supported);
        var command = org.mockito.ArgumentCaptor.forClass(AsyncCommandArgument.class);
        verify(slave).sendCommand(command.capture());
        assertEquals("maxpath", command.getValue().getName());
        assertEquals("idle-crc-audit-v1", command.getValue().getArgs());
    }

    @Test void reconnectInvalidatesCachedCapabilityAndRestartsIdleGrace() throws Exception {
        var state = idle();
        state.supported = true;
        when(slave.getConnectionGeneration()).thenReturn(1L);
        audit.step(slave, state, 1000000);
        assertNull(state.supported);
        verify(slave, never()).sendCommand(any());
        when(slave.fetchResponseWithoutDisconnect("a1", 15000))
                .thenReturn(new AsyncResponseMaxPath("a1", IdleChecksumScanner.CAPABILITY));
        audit.step(slave, state, 1300000);
        assertTrue(state.supported);
    }

    @Test void expectedSfvCrcIsNeverReplacedWithMeasuredBadCrc() throws Exception {
        FileHandle file = mock(FileHandle.class);
        DirectoryHandle parent = mock(DirectoryHandle.class);
        when(file.getParent()).thenReturn(parent);
        when(file.getName()).thenReturn("file.rar");
        SFVInfo info = new SFVInfo();
        var entries = new CaseInsensitiveTreeMap<String, Long>();
        entries.put("file.rar", 123L);
        info.setEntries(entries);
        when(parent.getPluginMetaData(SFVInfo.SFVINFO)).thenReturn(info);
        AuditResult bad = AuditResult.measured("Evo", "/file.rar", 10, 20, 123L, "sfv", 456, 30);
        assertEquals(123L, audit.reference(file, bad).crc());
        verify(file, never()).getCheckSum();
        verify(file, never()).getCheckSumFromSlave();
    }

    @Test void missingSfvUsesPreviousBaselineIncludingZero() throws Exception {
        FileHandle file = mock(FileHandle.class);
        DirectoryHandle parent = mock(DirectoryHandle.class);
        when(file.getParent()).thenReturn(parent);
        when(file.getSize()).thenReturn(10L);
        when(file.lastModified()).thenReturn(20L);
        when(parent.getPluginMetaData(SFVInfo.SFVINFO)).thenThrow(new KeyNotFoundException());
        AuditResult baseline = AuditResult.measured("Evo", "/file", 10, 20, null, "none", 0, 30);
        assertEquals(0L, audit.reference(file, baseline).crc());
        assertEquals("baseline", audit.reference(file, baseline).source());
        assertNull(audit.reference(file, null).crc());
    }
}
