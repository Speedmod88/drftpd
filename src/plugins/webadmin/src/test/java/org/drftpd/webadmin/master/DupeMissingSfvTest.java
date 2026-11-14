package org.drftpd.webadmin.master;

import org.drftpd.master.vfs.DirectoryHandle;
import org.drftpd.master.vfs.FileHandle;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DupeMissingSfvTest {
    private DirectoryHandle directory(String name, String... names) throws Exception {
        var dir = mock(DirectoryHandle.class);
        when(dir.getName()).thenReturn(name);
        var files = new java.util.LinkedHashSet<FileHandle>();
        for (String filename : names) {
            var file = mock(FileHandle.class);
            when(file.getName()).thenReturn(filename);
            files.add(file);
        }
        when(dir.getFilesUnchecked()).thenReturn(files);
        when(dir.getInodeHandlesUnchecked()).thenReturn(new java.util.LinkedHashSet<>(files));
        return dir;
    }

    @Test void missingManifestHasSpecificLabelWithoutChangingOtherClassifications() throws Exception {
        var dir = directory("Release-GRP", "release.rar", "release.nfo");
        assertEquals("MISSING SFV", WebAdminDupeStatus.label(dir, "UNKNOWN"));
        assertEquals("NOT COMPLETED", WebAdminDupeStatus.label(dir, "NOT COMPLETED"));
        assertEquals("KEEP", WebAdminDupeStatus.label(dir, "KEEP"));
        for (var file : dir.getFilesUnchecked()) verify(file, never()).getCheckSumFromSlave();
    }

    @Test void existingSfvAndZipOrDizReleasesAreNotCalledMissingSfv() throws Exception {
        for (String manifest : List.of("Release.SFV", "release.ZIP", "FILE_ID.DIZ")) {
            assertEquals("UNKNOWN", WebAdminDupeStatus.label(directory("Release-GRP", manifest), "UNKNOWN"));
        }
    }

    @Test void recognizesDiscManifestsWithoutRequiringRootSfv() throws Exception {
        var root = directory("Release-GRP", "release.nfo");
        var cd1 = directory("CD1", "release.sfv", "release.rar");
        var cd2 = directory("CD2", "release.sfv", "release.rar");
        when(root.getInodeHandlesUnchecked()).thenReturn(java.util.Set.of(cd1, cd2));
        assertEquals("UNKNOWN", WebAdminDupeStatus.label(root, "UNKNOWN"));
        when(cd2.getFilesUnchecked()).thenReturn(java.util.Set.of());
        assertEquals("MISSING SFV", WebAdminDupeStatus.label(root, "UNKNOWN"));
    }

    @Test void unreadableDirectoryRemainsUnknown() throws Exception {
        var root = mock(DirectoryHandle.class);
        when(root.getFilesUnchecked()).thenThrow(new java.io.FileNotFoundException("gone"));
        assertEquals("UNKNOWN", WebAdminDupeStatus.label(root, "UNKNOWN"));
    }
}
