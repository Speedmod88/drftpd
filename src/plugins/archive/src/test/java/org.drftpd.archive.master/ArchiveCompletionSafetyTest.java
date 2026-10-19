package org.drftpd.archive.master;

import org.drftpd.archive.master.archivetypes.ReleaseOrganization;
import org.drftpd.common.dynamicdata.KeyNotFoundException;
import org.drftpd.master.commands.nuke.metadata.NukeData;
import org.drftpd.master.vfs.*;
import org.drftpd.zipscript.common.zip.DizInfo;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ArchiveCompletionSafetyTest {
    private DirectoryHandle release;
    private FileHandle file;

    private void fixture() throws Exception {
        release = mock(DirectoryHandle.class);
        DirectoryHandle root = mock(DirectoryHandle.class);
        when(root.isRoot()).thenReturn(true);
        when(release.getParent()).thenReturn(root);
        when(release.getName()).thenReturn("Show.E01.FRENCH.WEB-GRP");
        when(release.getPluginMetaData(NukeData.NUKEDATA)).thenThrow(new KeyNotFoundException());
        file = mock(FileHandle.class);
        when(file.getName()).thenReturn("release.zip");
        when(file.getSize()).thenReturn(100L);
        when(release.getFilesUnchecked()).thenReturn(Set.of(file));
        when(release.getInodeHandlesUnchecked()).thenReturn(Set.of(file));
        DizInfo diz = new DizInfo();
        diz.setValid(true);
        diz.setTotal(1);
        when(release.getPluginMetaData(DizInfo.DIZINFO)).thenReturn(diz);
    }

    @Test void completedIsEligibleWithoutRemoteChecksum() throws Exception {
        fixture();
        assertNull(ReleaseOrganization.unsafeReason(release));
        verify(file, never()).getCheckSum();
        verify(file, never()).getCheckSumFromSlave();
    }
    @Test void activeOrIncompleteNeverMoves() throws Exception {
        fixture();
        when(file.isTransferring()).thenReturn(true);
        assertEquals("ACTIVE TRANSFER", ReleaseOrganization.unsafeReason(release));
        when(file.isTransferring()).thenReturn(false);
        when(file.getSize()).thenReturn(0L);
        assertEquals("INCOMPLETE FILE", ReleaseOrganization.unsafeReason(release));
    }
    @Test void unknownAndNukedNeverMove() throws Exception {
        fixture();
        when(release.getPluginMetaData(DizInfo.DIZINFO)).thenThrow(new KeyNotFoundException());
        assertEquals("UNKNOWN", ReleaseOrganization.unsafeReason(release));
        when(release.getName()).thenReturn("[NUKED]-Show.E01.FRENCH.WEB-GRP");
        assertEquals("NUKED / INCOMPLETE MARKER", ReleaseOrganization.unsafeReason(release));
    }
    @Test void unverifiedPayloadAndLinksNeverMove() throws Exception {
        fixture();
        when(file.getName()).thenReturn("release.rar");
        assertEquals("UNKNOWN", ReleaseOrganization.unsafeReason(release));
        when(release.isLink()).thenReturn(true);
        assertEquals("SYMLINK", ReleaseOrganization.unsafeReason(release));
    }
}
