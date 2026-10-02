package org.drftpd.links.master;

import org.drftpd.common.dynamicdata.KeyNotFoundException;
import org.drftpd.common.vfs.CaseInsensitiveTreeMap;
import org.drftpd.master.vfs.DirectoryHandle;
import org.drftpd.master.vfs.FileHandle;
import org.drftpd.zipscript.common.sfv.SFVInfo;
import org.junit.jupiter.api.Test;
import java.util.Properties;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LinkStatusReconciliationTest {
    static class RecordingLink extends LinkType {
        int created;
        int deleted;
        RecordingLink(String type) { super(properties(), 1, type); }
        static Properties properties() {
            Properties p = new Properties();
            p.setProperty("1.linkname", "marker");
            return p;
        }
        @Override public void doCreateLink(DirectoryHandle dir) { created++; }
        @Override public void doDeleteLink(DirectoryHandle dir) { deleted++; }
        @Override public void doFixLink(DirectoryHandle dir) { fail("Background reconciliation must not invoke a CRC rescan"); }
    }

    @Test void unknownPreservesMarkerAndLaterCachedCompletionRemovesIt() throws Exception {
        DirectoryHandle dir = mock(DirectoryHandle.class);
        FileHandle sfv = mock(FileHandle.class);
        FileHandle member = mock(FileHandle.class);
        when(dir.getFilesUnchecked()).thenReturn(Set.of(sfv, member));
        when(sfv.getName()).thenReturn("release.sfv");
        when(member.getName()).thenReturn("release.rar");
        when(dir.getPluginMetaData(SFVInfo.SFVINFO)).thenThrow(new KeyNotFoundException());
        RecordingLink link = new RecordingLink("sfvincomplete");
        assertFalse(link.reconcileStatus(dir));
        assertEquals(0, link.deleted);
        SFVInfo info = new SFVInfo();
        info.setSFVFileName("release.sfv");
        info.setChecksum(11);
        var entries = new CaseInsensitiveTreeMap<String, Long>();
        entries.put("release.rar", 22L);
        info.setEntries(entries);
        doReturn(info).when(dir).getPluginMetaData(SFVInfo.SFVINFO);
        when(sfv.getCheckSumCached()).thenReturn(11L);
        when(member.getCheckSumCached()).thenReturn(22L);
        when(sfv.getSize()).thenReturn(10L);
        when(member.getSize()).thenReturn(100L);
        assertTrue(link.reconcileStatus(dir));
        assertEquals(1, link.deleted);
        assertEquals(0, link.created);
        verify(member, never()).getCheckSum();
        when(member.getCheckSumCached()).thenReturn(33L);
        assertTrue(link.reconcileStatus(dir));
        assertEquals(1, link.created);
    }

    @Test void vfsRefreshDoesNotCreateMissingSfvMarkersForInternalFolders() throws Exception {
        DirectoryHandle dir = mock(DirectoryHandle.class);
        when(dir.getFilesUnchecked()).thenReturn(Set.of());
        RecordingLink link = new RecordingLink("sfvmissing");
        assertTrue(link.reconcileStatus(dir));
        assertEquals(0, link.created);
        assertEquals(0, link.deleted);
    }
}
