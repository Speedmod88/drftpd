package org.drftpd.webadmin.master;

import org.drftpd.master.vfs.DirectoryHandle;
import java.io.FileNotFoundException;
import java.util.Locale;
import java.util.regex.Pattern;

/** Display reasons only; the trusted completion classification remains unchanged. */
final class WebAdminDupeStatus {
    private static final Pattern DISC = Pattern.compile("(?i)(?:CD|DISC|DISK|DVD)[._ -]?[0-9]{1,3}");

    static String label(DirectoryHandle directory, String status) {
        return status.equals("UNKNOWN") && missingSfv(directory, 0, new int[]{128}) ? "MISSING SFV" : status;
    }

    private static boolean missingSfv(DirectoryHandle directory, int depth, int[] remaining) {
        if (depth > 8 || --remaining[0] < 0) return false;
        try {
            for (var file : directory.getFilesUnchecked()) {
                String name = file.getName().toLowerCase(Locale.ROOT);
                if (name.endsWith(".sfv") || name.endsWith(".zip") || name.equals("file_id.diz")) return false;
            }
            boolean hasDiscs = false, missingDiscSfv = false;
            for (var inode : directory.getInodeHandlesUnchecked()) {
                if (!inode.isLink() && inode instanceof DirectoryHandle child && DISC.matcher(child.getName()).matches()) {
                    hasDiscs = true;
                    missingDiscSfv |= missingSfv(child, depth + 1, remaining);
                }
            }
            return !hasDiscs || missingDiscSfv;
        } catch (FileNotFoundException e) {
            return false;
        }
    }
}
