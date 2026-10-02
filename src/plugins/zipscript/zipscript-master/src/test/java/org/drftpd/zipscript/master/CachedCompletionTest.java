package org.drftpd.zipscript.master;

import org.drftpd.common.dynamicdata.KeyNotFoundException;
import org.drftpd.common.vfs.CaseInsensitiveTreeMap;
import org.drftpd.master.vfs.DirectoryHandle;
import org.drftpd.master.vfs.FileHandle;
import org.drftpd.zipscript.common.sfv.SFVInfo;
import org.drftpd.zipscript.common.zip.DizInfo;
import org.junit.jupiter.api.Test;
import java.io.FileNotFoundException;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CachedCompletionTest {
    private final DirectoryHandle dir = mock(DirectoryHandle.class);
    private final FileHandle sfv = mock(FileHandle.class);
    private final FileHandle member = mock(FileHandle.class);

    private void fixture() throws Exception {
        SFVInfo info = new SFVInfo();
        info.setSFVFileName("release.sfv");
        info.setChecksum(123);
        var entries = new CaseInsensitiveTreeMap<String, Long>();
        entries.put("release.rar", 456L);
        info.setEntries(entries);
        when(dir.getPluginMetaData(SFVInfo.SFVINFO)).thenReturn(info);
        when(dir.getFilesUnchecked()).thenReturn(Set.of(sfv, member));
        when(dir.getFileUnchecked("release.sfv")).thenReturn(sfv);
        when(dir.getFileUnchecked("release.rar")).thenReturn(member);
        when(sfv.getName()).thenReturn("release.sfv");
        when(member.getName()).thenReturn("release.rar");
        when(sfv.getSize()).thenReturn(20L);
        when(member.getSize()).thenReturn(200L);
        when(sfv.getCheckSumCached()).thenReturn(123L);
        when(member.getCheckSumCached()).thenReturn(456L);
    }

    @Test void completeUsesOnlyCachedChecksums() throws Exception {
        fixture();
        assertEquals(CachedCompletion.Status.COMPLETE, CachedCompletion.release(dir));
        verify(sfv, never()).getCheckSum();
        verify(member, never()).getCheckSum();
        verify(member, never()).getCheckSumFromSlave();
    }
    @Test void missingCrcIsUnknownNotCompleteAndDoesNotFetch() throws Exception {
        fixture();
        when(member.getCheckSumCached()).thenReturn(0L);
        assertEquals(CachedCompletion.Status.UNKNOWN, CachedCompletion.release(dir));
        verify(member, never()).getCheckSum();
    }
    @Test void uploadingAndMismatchedFilesAreIncomplete() throws Exception {
        fixture();
        when(member.isUploading()).thenReturn(true);
        assertEquals(CachedCompletion.Status.INCOMPLETE, CachedCompletion.release(dir));
        when(member.isUploading()).thenReturn(false);
        when(member.getCheckSumCached()).thenReturn(789L);
        assertEquals(CachedCompletion.Status.INCOMPLETE, CachedCompletion.release(dir));
    }
    @Test void missingFileAndTruncatedFileAreIncomplete() throws Exception {
        fixture();
        when(member.getSize()).thenReturn(0L);
        assertEquals(CachedCompletion.Status.INCOMPLETE, CachedCompletion.release(dir));
        when(dir.getFilesUnchecked()).thenReturn(Set.of(sfv));
        assertEquals(CachedCompletion.Status.INCOMPLETE, CachedCompletion.release(dir));
    }
    @Test void sfvMemberNamesAreCaseInsensitive() throws Exception {
        fixture();
        when(member.getName()).thenReturn("RELEASE.RAR");
        assertEquals(CachedCompletion.Status.COMPLETE, CachedCompletion.release(dir));
    }
    @Test void missingOrChangedSfvMetadataIsUnknown() throws Exception {
        fixture();
        when(sfv.getCheckSumCached()).thenReturn(789L);
        assertEquals(CachedCompletion.Status.UNKNOWN, CachedCompletion.release(dir));
        when(dir.getPluginMetaData(SFVInfo.SFVINFO)).thenThrow(new KeyNotFoundException());
        assertEquals(CachedCompletion.Status.UNKNOWN, CachedCompletion.release(dir));
        verify(sfv, never()).getCheckSum();
    }
    @Test void zipNeedsCachedDizAndFinishedNonemptyFiles() throws Exception {
        when(member.getName()).thenReturn("release.zip");
        when(member.getSize()).thenReturn(200L);
        when(dir.getFilesUnchecked()).thenReturn(Set.of(member));
        when(dir.getPluginMetaData(DizInfo.DIZINFO)).thenThrow(new KeyNotFoundException());
        assertEquals(CachedCompletion.Status.UNKNOWN, CachedCompletion.release(dir));
        DizInfo diz = new DizInfo();
        diz.setValid(true);
        diz.setTotal(1);
        doReturn(diz).when(dir).getPluginMetaData(DizInfo.DIZINFO);
        assertEquals(CachedCompletion.Status.COMPLETE, CachedCompletion.release(dir));
        when(member.isUploading()).thenReturn(true);
        assertEquals(CachedCompletion.Status.INCOMPLETE, CachedCompletion.release(dir));
    }
}
