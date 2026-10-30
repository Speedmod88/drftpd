package org.drftpd.zipscript.master.nfo;

import org.drftpd.common.extensibility.CommandHook;
import org.drftpd.common.extensibility.HookType;
import org.drftpd.master.GlobalContext;
import org.drftpd.master.commands.CommandRequest;
import org.drftpd.master.commands.CommandResponse;
import org.drftpd.master.usermanager.User;
import org.drftpd.master.vfs.DirectoryHandle;
import org.drftpd.master.vfs.FileHandle;
import org.drftpd.zipscript.master.CachedCompletion;
import java.util.Comparator;
import java.util.List;

public final class NfoDisplayHook {
    @CommandHook(commands = "doCWD", priority = 100, type = HookType.POST)
    public void showNfo(CommandRequest request, CommandResponse response) {
        NfoDisplay display = NfoDisplay.current();
        if (response.getCode() != 250 || response.getCurrentDirectory() == null || display == null || !display.enabled()) return;
        DirectoryHandle directory = response.getCurrentDirectory();
        try {
            if (CachedCompletion.release(directory) != CachedCompletion.Status.COMPLETE) return;
            User user = request.getSession().getUserNull(request.getUser());
            if (!request.getSession().isSecure() && GlobalContext.getConfig().checkPermission("denydatauncrypted", user)) return;
            List<FileHandle> files = directory.getFilesUnchecked().stream()
                    .sorted(Comparator.comparing(FileHandle::getName)).toList();
            for (FileHandle file : files) {
                if (!file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".nfo") || file.isHidden(user) || file.isLink()
                        || !GlobalContext.getConfig().getVFSPermissions().checkPathPermission("download", user, file)) continue;
                List<String> lines = display.lines(file);
                // Recheck after reading the cache so a new upload or deletion cannot reveal stale completion.
                if (!lines.isEmpty() && CachedCompletion.release(directory) == CachedCompletion.Status.COMPLETE) {
                    response.addComment("NFO: " + file.getName());
                    for (String line : lines) response.addComment(line);
                }
                break;
            }
        } catch (Exception ignored) {
            // Optional display must never fail a successful CWD.
        }
    }
}
