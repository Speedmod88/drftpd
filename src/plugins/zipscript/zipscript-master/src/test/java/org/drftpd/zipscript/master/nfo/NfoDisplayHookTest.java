package org.drftpd.zipscript.master.nfo;

import org.drftpd.master.GlobalContext;
import org.drftpd.master.commands.CommandRequest;
import org.drftpd.master.commands.CommandResponse;
import org.drftpd.master.config.ConfigInterface;
import org.drftpd.master.network.BaseFtpConnection;
import org.drftpd.master.usermanager.User;
import org.drftpd.master.vfs.DirectoryHandle;
import org.drftpd.master.vfs.FileHandle;
import org.drftpd.master.vfs.perms.VFSPermissions;
import org.drftpd.zipscript.master.CachedCompletion;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NfoDisplayHookTest {
    @Test void incompleteAndUnknownNeverReadCacheOrQueueSlaveWork() throws Exception {
        try (var active = mockStatic(NfoDisplay.class); var completion = mockStatic(CachedCompletion.class)) {
            NfoDisplay display = mock(NfoDisplay.class);
            active.when(NfoDisplay::current).thenReturn(display);
            when(display.enabled()).thenReturn(true);
            DirectoryHandle dir = mock(DirectoryHandle.class);
            for (var state : List.of(CachedCompletion.Status.INCOMPLETE, CachedCompletion.Status.UNKNOWN)) {
                completion.when(() -> CachedCompletion.release(dir)).thenReturn(state);
                CommandResponse response = new CommandResponse(250, "Directory changed", dir, "user");
                new NfoDisplayHook().showNfo(mock(CommandRequest.class), response);
                assertTrue(response.getComment().isEmpty());
            }
            verify(display, never()).lines(any());
        }
    }

    @Test void completeReleaseDisplaysCachedNfoAndRespectsDownloadPermission() throws Exception {
        try (var active = mockStatic(NfoDisplay.class); var completion = mockStatic(CachedCompletion.class);
             var global = mockStatic(GlobalContext.class)) {
            NfoDisplay display = mock(NfoDisplay.class);
            active.when(NfoDisplay::current).thenReturn(display);
            when(display.enabled()).thenReturn(true);
            DirectoryHandle dir = mock(DirectoryHandle.class);
            completion.when(() -> CachedCompletion.release(dir)).thenReturn(CachedCompletion.Status.COMPLETE);
            FileHandle file = mock(FileHandle.class);
            when(file.getName()).thenReturn("release.nfo");
            when(dir.getFilesUnchecked()).thenReturn(java.util.Set.of(file));
            when(display.lines(file)).thenReturn(List.of("Information", "Second line"));
            User user = mock(User.class);
            CommandRequest request = mock(CommandRequest.class);
            BaseFtpConnection conn = mock(BaseFtpConnection.class);
            when(request.getSession()).thenReturn(conn);
            when(conn.getUserNull(request.getUser())).thenReturn(user);
            ConfigInterface config = mock(ConfigInterface.class);
            VFSPermissions permissions = mock(VFSPermissions.class);
            global.when(GlobalContext::getConfig).thenReturn(config);
            when(config.getVFSPermissions()).thenReturn(permissions);
            when(permissions.checkPathPermission("download", user, file)).thenReturn(true);
            CommandResponse response = new CommandResponse(250, "Directory changed", dir, "user");
            new NfoDisplayHook().showNfo(request, response);
            assertEquals(List.of("NFO: release.nfo", "Information", "Second line"), response.getComment());
            when(permissions.checkPathPermission("download", user, file)).thenReturn(false);
            response = new CommandResponse(250, "Directory changed", dir, "user");
            new NfoDisplayHook().showNfo(request, response);
            assertTrue(response.getComment().isEmpty());
            when(permissions.checkPathPermission("download", user, file)).thenReturn(true);
            completion.when(() -> CachedCompletion.release(dir))
                    .thenReturn(CachedCompletion.Status.COMPLETE, CachedCompletion.Status.INCOMPLETE);
            response = new CommandResponse(250, "Directory changed", dir, "user");
            new NfoDisplayHook().showNfo(request, response);
            assertTrue(response.getComment().isEmpty(), "Completion must still hold when displaying cached text");
        }
    }
}
