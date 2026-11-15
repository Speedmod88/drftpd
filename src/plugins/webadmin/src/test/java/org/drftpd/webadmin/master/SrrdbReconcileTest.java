package org.drftpd.webadmin.master;

import org.drftpd.master.GlobalContext;
import org.drftpd.master.config.ConfigInterface;
import org.drftpd.master.usermanager.User;
import org.drftpd.master.vfs.DirectoryHandle;
import org.drftpd.master.vfs.FileHandle;
import org.drftpd.master.slavemanagement.RemoteSlave;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SrrdbReconcileTest {
    @Test void freshReplicaChecksumsAreRequiredAndActiveOrMismatchedFilesAreKept() throws Exception {
        var global = mock(GlobalContext.class, RETURNS_DEEP_STUBS);
        var config = mock(ConfigInterface.class);
        var actor = mock(User.class);
        when(actor.isMemberOf("siteop")).thenReturn(true);
        when(config.isLoginAllowed(actor)).thenReturn(true);
        when(global.getUserManager().getUserByNameIncludeDeleted("siteop")).thenReturn(actor);
        var root = mock(DirectoryHandle.class);
        var section = mock(DirectoryHandle.class);
        var release = mock(DirectoryHandle.class);
        when(global.getRoot()).thenReturn(root);
        when(root.getInodeHandle("SECTION", actor)).thenReturn(section);
        when(section.isDirectory()).thenReturn(true);
        when(section.getInodeHandle("Release-GRP", actor)).thenReturn(release);
        when(release.isDirectory()).thenReturn(true);
        var file = mock(FileHandle.class);
        when(file.getName()).thenReturn("release.nfo");
        when(file.getPath()).thenReturn("/SECTION/Release-GRP/release.nfo");
        when(file.getParent()).thenReturn(release);
        when(file.getSize()).thenReturn(337L);
        when(release.getInodeHandlesUnchecked()).thenReturn(Set.of(file));
        var slave = mock(RemoteSlave.class, RETURNS_DEEP_STUBS);
        when(slave.isAvailable()).thenReturn(true);
        when(slave.getRenameQueue().isEmpty()).thenReturn(true);
        when(file.getSlaves()).thenReturn(Set.of(slave));
        when(slave.getCheckSumForPath(file.getPath())).thenReturn(0x8CCA8FD7L);
        var remote = new SrrdbClient.RemoteFile("release.nfo", 337, "8CCA8FD7");
        var uploads = mock(SrrdbFtpUpload.class);
        try (var context = mockStatic(GlobalContext.class)) {
            context.when(GlobalContext::getGlobalContext).thenReturn(global);
            context.when(GlobalContext::getConfig).thenReturn(config);
            var library = new SrrdbLibrary(uploads);
            assertNotNull(library.reconcile("siteop", "/SECTION/Release-GRP", remote));
            verify(file).setCheckSum(0x8CCA8FD7L);
            verify(slave).getCheckSumForPath(file.getPath());
            when(slave.getCheckSumForPath(file.getPath())).thenReturn(123L);
            assertThrows(java.io.IOException.class, () -> library.reconcile("siteop", "/SECTION/Release-GRP", remote));
            when(file.isTransferring()).thenReturn(true);
            assertThrows(java.io.IOException.class, () -> library.reconcile("siteop", "/SECTION/Release-GRP", remote));
            when(file.isTransferring()).thenReturn(false);
            when(file.getSize()).thenReturn(10L);
            assertThrows(java.io.IOException.class, () -> library.reconcile("siteop", "/SECTION/Release-GRP", remote));
            when(file.getSize()).thenReturn(0L);
            assertNull(library.reconcile("siteop", "/SECTION/Release-GRP", remote));
            verifyNoInteractions(uploads);
        }
    }
}
