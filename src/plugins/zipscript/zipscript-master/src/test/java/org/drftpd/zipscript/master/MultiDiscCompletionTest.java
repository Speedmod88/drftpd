package org.drftpd.zipscript.master;

import org.drftpd.common.dynamicdata.KeyNotFoundException;
import org.drftpd.common.vfs.CaseInsensitiveTreeMap;
import org.drftpd.master.vfs.DirectoryHandle;
import org.drftpd.master.vfs.FileHandle;
import org.drftpd.master.vfs.InodeHandle;
import org.drftpd.zipscript.common.sfv.SFVInfo;
import org.drftpd.zipscript.common.zip.DizInfo;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MultiDiscCompletionTest {
    private DirectoryHandle container(String name, InodeHandle... children) throws Exception {
        DirectoryHandle dir = mock(DirectoryHandle.class);
        when(dir.getName()).thenReturn(name);
        when(dir.getFilesUnchecked()).thenReturn(Set.of());
        when(dir.getInodeHandlesUnchecked()).thenReturn(Set.of(children));
        when(dir.getPluginMetaData(DizInfo.DIZINFO)).thenThrow(new KeyNotFoundException());
        return dir;
    }

    private DirectoryHandle disc(String name, long actualCrc) throws Exception {
        DirectoryHandle dir = container(name);
        FileHandle sfv = mock(FileHandle.class), member = mock(FileHandle.class);
        when(sfv.getName()).thenReturn("release.sfv");
        when(member.getName()).thenReturn("release.rar");
        when(sfv.getSize()).thenReturn(20L);
        when(member.getSize()).thenReturn(200L);
        when(sfv.getCheckSumCached()).thenReturn(123L);
        when(member.getCheckSumCached()).thenReturn(actualCrc);
        when(dir.getFilesUnchecked()).thenReturn(Set.of(sfv, member));
        SFVInfo info = new SFVInfo();
        info.setSFVFileName("release.sfv");
        info.setChecksum(123L);
        var entries = new CaseInsensitiveTreeMap<String, Long>();
        entries.put("release.rar", 456L);
        info.setEntries(entries);
        when(dir.getPluginMetaData(SFVInfo.SFVINFO)).thenReturn(info);
        return dir;
    }

    @Test void autodeskThreeDiscReleaseIsCompleteFromCachedMetadata() throws Exception {
        DirectoryHandle root = container("AUTODESK.CIVIL3D.V2021.FRENCH-ENGiNE",
                disc("DISK1", 456), disc("DISK2", 456), disc("DISK3", 456));
        assertEquals(CachedCompletion.Status.COMPLETE, CachedCompletion.release(root));
        assertEquals(CachedCompletion.Status.COMPLETE, CachedCompletion.release(root));
    }

    @Test void childUnknownAndMismatchAreNotComplete() throws Exception {
        assertEquals(CachedCompletion.Status.UNKNOWN,
                CachedCompletion.release(container("release", disc("DISK1", 456), disc("DISK2", 0))));
        assertEquals(CachedCompletion.Status.INCOMPLETE,
                CachedCompletion.release(container("release", disc("DISK1", 456), disc("DISK2", 789))));
    }

    @Test void discAliasesAndAuxiliaryNfoAreSupported() throws Exception {
        FileHandle nfo = mock(FileHandle.class);
        when(nfo.getName()).thenReturn("release.nfo");
        for (String name : new String[]{"CD1", "Disc01", "DISK-1", "DVD.1"}) {
            assertEquals(CachedCompletion.Status.COMPLETE,
                    CachedCompletion.release(container("release", nfo, disc(name, 456))), name);
        }
    }

    @Test void gapsDuplicateNumbersAndArbitraryFoldersRemainUnknown() throws Exception {
        assertEquals(CachedCompletion.Status.UNKNOWN,
                CachedCompletion.release(container("release", disc("DISK2", 456))));
        assertEquals(CachedCompletion.Status.UNKNOWN,
                CachedCompletion.release(container("release", disc("DISK1", 456), disc("DISK3", 456))));
        assertEquals(CachedCompletion.Status.UNKNOWN,
                CachedCompletion.release(container("release", disc("CD1", 456), disc("DISK1", 456))));
        assertEquals(CachedCompletion.Status.UNKNOWN,
                CachedCompletion.release(container("section", disc("Release-GRP", 456))));
    }

    @Test void unverifiedPayloadOrLinkedDiscCannotAuthorizeDeletion() throws Exception {
        FileHandle file = mock(FileHandle.class);
        when(file.getName()).thenReturn("extra.iso");
        assertEquals(CachedCompletion.Status.UNKNOWN,
                CachedCompletion.release(container("release", file, disc("DISK1", 456))));
        DirectoryHandle linked = disc("DISK2", 456);
        when(linked.isLink()).thenReturn(true);
        assertEquals(CachedCompletion.Status.UNKNOWN,
                CachedCompletion.release(container("release", disc("DISK1", 456), linked)));
    }

    @Test void recursionIsBounded() throws Exception {
        DirectoryHandle dir = disc("DISK1", 456);
        for (int i = 0; i < 10; i++) dir = container("DISK1", dir);
        assertEquals(CachedCompletion.Status.UNKNOWN, CachedCompletion.release(dir));
    }

    @Test void childBecomesCompleteWithoutCachingAnUnsafeParentFlag() throws Exception {
        DirectoryHandle child = container("DISK1");
        DirectoryHandle root = container("release", child);
        assertEquals(CachedCompletion.Status.UNKNOWN, CachedCompletion.release(root));
        DirectoryHandle complete = disc("DISK1", 456);
        when(root.getInodeHandlesUnchecked()).thenReturn(Set.of(complete));
        assertEquals(CachedCompletion.Status.COMPLETE, CachedCompletion.release(root));
        DirectoryHandle corrupt = disc("DISK1", 789);
        when(root.getInodeHandlesUnchecked()).thenReturn(Set.of(corrupt));
        assertEquals(CachedCompletion.Status.INCOMPLETE, CachedCompletion.release(root));
    }
}
