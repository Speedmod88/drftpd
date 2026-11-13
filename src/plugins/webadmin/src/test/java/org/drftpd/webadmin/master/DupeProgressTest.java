package org.drftpd.webadmin.master;

import org.drftpd.common.vfs.CaseInsensitiveTreeMap;
import org.drftpd.common.dynamicdata.KeyNotFoundException;
import org.drftpd.master.vfs.DirectoryHandle;
import org.drftpd.master.vfs.FileHandle;
import org.drftpd.zipscript.common.sfv.SFVInfo;
import org.drftpd.zipscript.common.zip.DizInfo;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DupeProgressTest {
    private FileHandle file(String name, long crc) throws Exception {
        var file = mock(FileHandle.class);
        when(file.getName()).thenReturn(name);
        when(file.getSize()).thenReturn(100L);
        when(file.getCheckSumCached()).thenReturn(crc);
        return file;
    }

    private DirectoryHandle sfv(FileHandle... members) throws Exception {
        var dir = mock(DirectoryHandle.class);
        var sfv = file("release.sfv", 123);
        var files = new java.util.HashSet<FileHandle>(); files.add(sfv); files.addAll(java.util.List.of(members));
        when(dir.getFilesUnchecked()).thenReturn(files);
        var info = new SFVInfo(); info.setSFVFileName("release.sfv"); info.setChecksum(123);
        var entries = new CaseInsensitiveTreeMap<String, Long>();
        entries.put("release.r00", 456L); entries.put("release.r01", 789L);
        info.setEntries(entries); when(dir.getPluginMetaData(SFVInfo.SFVINFO)).thenReturn(info);
        return dir;
    }

    @Test void reportsPartialAndCompleteWithoutRemoteChecksumRequests() throws Exception {
        var a = file("RELEASE.R00", 456); var b = file("release.r01", 789);
        assertEquals(new WebAdminDupeProgress.Progress(50, 1, 2), WebAdminDupeProgress.read(sfv(a)));
        assertEquals(new WebAdminDupeProgress.Progress(100, 2, 2), WebAdminDupeProgress.read(sfv(a, b)));
        verify(a, never()).getCheckSum(); verify(a, never()).getCheckSumFromSlave();
        verify(b, never()).getCheckSum();
    }

    @Test void unknownCrcOrUntrustedManifestNeverShowsOneHundredPercent() throws Exception {
        var a = file("release.r00", 0); var b = file("release.r01", 789);
        assertNull(WebAdminDupeProgress.read(sfv(a, b)).percent());
        var dir = sfv(file("release.r00", 456), b);
        var info = (SFVInfo) dir.getPluginMetaData(SFVInfo.SFVINFO); info.setChecksum(999);
        assertNull(WebAdminDupeProgress.read(dir).percent());
    }

    @Test void activeZeroByteAndMismatchedPayloadFilesDoNotCountAsComplete() throws Exception {
        var a = file("release.r00", 456); var b = file("release.r01", 999);
        var dir = sfv(a, b);
        assertEquals(50, WebAdminDupeProgress.read(dir).percent());
        when(a.isUploading()).thenReturn(true);
        assertEquals(0, WebAdminDupeProgress.read(dir).percent());
        when(a.isUploading()).thenReturn(false); when(a.getSize()).thenReturn(0L);
        assertEquals(0, WebAdminDupeProgress.read(dir).percent());
    }

    @Test void absentCachedManifestRemainsUnknown() throws Exception {
        var dir = sfv(file("release.r00", 456));
        doReturn(null).when(dir).getPluginMetaData(SFVInfo.SFVINFO);
        assertNull(WebAdminDupeProgress.read(dir).percent());
        doThrow(new KeyNotFoundException()).when(dir).getPluginMetaData(SFVInfo.SFVINFO);
        assertNull(WebAdminDupeProgress.read(dir).percent());
    }

    @Test void zipUsesCachedDizExpectedCount() throws Exception {
        var dir = mock(DirectoryHandle.class);
        var a = file("release.zip", 0);
        when(dir.getFilesUnchecked()).thenReturn(Set.of(a));
        var info = new DizInfo(); info.setValid(true); info.setTotal(4);
        when(dir.getPluginMetaData(DizInfo.DIZINFO)).thenReturn(info);
        assertEquals(new WebAdminDupeProgress.Progress(25, 1, 4), WebAdminDupeProgress.read(dir));
        info.setTotal(0);
        assertNull(WebAdminDupeProgress.read(dir).percent());
    }

    @Test void numberedDiscsAreWeightedByFileCountAndContainersStayUnknown() throws Exception {
        var disc1 = sfv(file("release.r00", 456), file("release.r01", 789));
        var disc2 = sfv(file("release.r00", 456));
        when(disc1.getName()).thenReturn("CD1"); when(disc2.getName()).thenReturn("CD2");
        var root = mock(DirectoryHandle.class);
        when(root.getFilesUnchecked()).thenReturn(Set.of());
        when(root.getPluginMetaData(DizInfo.DIZINFO)).thenThrow(new KeyNotFoundException());
        when(root.getInodeHandlesUnchecked()).thenReturn(Set.of(disc1, disc2));
        assertEquals(new WebAdminDupeProgress.Progress(75, 3, 4), WebAdminDupeProgress.read(root));
        when(disc1.getName()).thenReturn("Season.01");
        assertNull(WebAdminDupeProgress.read(root).percent());
    }
}
