package org.drftpd.webadmin.master;

import org.drftpd.master.GlobalContext;
import org.drftpd.master.vfs.DirectoryHandle;
import org.drftpd.master.vfs.FileHandle;
import org.drftpd.master.vfs.event.VirtualFileSystemInodeRefreshEvent;
import org.drftpd.zipscript.master.CachedCompletion;
import org.drftpd.zipscript.master.sfv.vfs.ZipscriptVFSDataSFV;
import org.drftpd.zipscript.master.zip.vfs.ZipscriptVFSDataZip;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.function.Consumer;

/** Explicit background metadata refresh; never invokes the destructive SITE RESCAN action. */
final class WebAdminDupeVerification {
    static boolean readable(FileHandle file) throws IOException {
        return !file.isTransferring() && file.getXfertime() != -1 && file.getSize() > 0;
    }
    static void verify(String username, String path, Consumer<String> output) throws Exception {
        DirectoryHandle root = WebAdminDupe.directory(WebAdminDupe.user(username), path);
        if (CachedCompletion.release(root) != CachedCompletion.Status.UNKNOWN) {
            output.accept("Already classified: " + path); return;
        }
        ArrayDeque<DirectoryHandle> pending = new ArrayDeque<>();
        pending.add(root);
        int visited = 0;
        while (!pending.isEmpty()) {
            DirectoryHandle dir = WebAdminDupe.directory(WebAdminDupe.user(username), pending.removeFirst().getPath());
            boolean sfv = false, zip = false;
            for (var inode : dir.getInodeHandlesUnchecked()) {
                if (++visited > 10000) throw new IOException("Verification entry limit reached");
                if (inode.isLink()) continue;
                if (inode instanceof DirectoryHandle child) { pending.add(child); continue; }
                FileHandle file = (FileHandle) inode;
                if (file.isHidden(WebAdminDupe.user(username))) continue;
                if (!readable(file)) {
                    output.accept("SKIP active/unfinished file: " + file.getPath()); continue;
                }
                String name = file.getName().toLowerCase(Locale.ROOT);
                sfv |= name.endsWith(".sfv");
                zip |= name.endsWith(".zip");
                if (name.endsWith(".sfv") || file.getCheckSumCached() == 0) {
                    output.accept("Checking CRC: " + file.getPath());
                    file.getCheckSumFromSlave();
                }
            }
            if (sfv) new ZipscriptVFSDataSFV(dir).getSFVInfo();
            else if (zip) new ZipscriptVFSDataZip(dir).getDizInfo();
            GlobalContext.getEventService().publishAsync(new VirtualFileSystemInodeRefreshEvent(dir.getInode(), dir.getPath()));
            output.accept(CachedCompletion.release(dir).name() + ": " + dir.getPath());
        }
        output.accept("Verification finished; no files deleted: " + path);
    }
}
