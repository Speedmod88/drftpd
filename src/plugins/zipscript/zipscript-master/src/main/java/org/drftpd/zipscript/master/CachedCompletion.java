package org.drftpd.zipscript.master;

import org.drftpd.common.dynamicdata.KeyNotFoundException;
import org.drftpd.master.vfs.DirectoryHandle;
import org.drftpd.master.vfs.FileHandle;
import org.drftpd.zipscript.common.sfv.SFVInfo;
import org.drftpd.zipscript.common.zip.DizInfo;
import org.drftpd.zipscript.master.audit.AuditFailure;

import java.io.FileNotFoundException;
import java.util.Locale;

/** Read-only VFS checks: never fetch metadata or checksums from a slave. */
public final class CachedCompletion {
    public enum Status { COMPLETE, INCOMPLETE, UNKNOWN }

    private CachedCompletion() { }

    public static Status release(DirectoryHandle dir) {
        return release(dir, 0, new int[]{128});
    }

    private static Status release(DirectoryHandle dir, int depth, int[] remaining) {
        if (depth > 8 || --remaining[0] < 0) return Status.UNKNOWN;
        try {
            boolean hasZip = false;
            for (FileHandle file : dir.getFilesUnchecked()) {
                if (file.getName().toLowerCase(Locale.ROOT).endsWith(".sfv")) return sfv(dir);
                hasZip |= file.getName().toLowerCase(Locale.ROOT).endsWith(".zip");
            }
            Status local = zip(dir);
            if (hasZip || local != Status.UNKNOWN) return local;
            return discs(dir, depth, remaining);
        } catch (FileNotFoundException e) {
            return Status.UNKNOWN;
        }
    }

    private static final java.util.regex.Pattern DISC_NAME =
            java.util.regex.Pattern.compile("(?i)(?:CD|DISC|DISK|DVD)[._ -]?([0-9]{1,3})");

    /** Only numbered-disc containers qualify; arbitrary section folders must stay UNKNOWN. */
    private static Status discs(DirectoryHandle dir, int depth, int[] remaining) throws FileNotFoundException {
        var discs = new java.util.TreeMap<Integer, DirectoryHandle>();
        for (var inode : dir.getInodeHandlesUnchecked()) {
            if (inode.isLink()) return Status.UNKNOWN;
            if (inode instanceof DirectoryHandle child) {
                var matcher = DISC_NAME.matcher(child.getName());
                if (!matcher.matches()) return Status.UNKNOWN;
                int number = Integer.parseInt(matcher.group(1));
                if (number < 1 || discs.put(number, child) != null) return Status.UNKNOWN;
            } else if (!inode.getName().toLowerCase(Locale.ROOT).matches(".*\\.(nfo|diz|txt)")) {
                // A payload outside the disc SFVs has not been validated.
                return Status.UNKNOWN;
            }
        }
        if (discs.isEmpty() || discs.size() != discs.lastKey()) return Status.UNKNOWN;
        boolean unknown = false;
        for (DirectoryHandle child : discs.values()) {
            Status status = release(child, depth + 1, remaining);
            if (status == Status.INCOMPLETE) return Status.INCOMPLETE;
            unknown |= status == Status.UNKNOWN;
        }
        return unknown ? Status.UNKNOWN : Status.COMPLETE;
    }

    public static Status sfv(DirectoryHandle dir) {
        try {
            SFVInfo info = dir.getPluginMetaData(SFVInfo.SFVINFO);
            if (info.getSFVFileName() == null || info.getEntries() == null) return Status.UNKNOWN;
            var files = new java.util.HashMap<String, FileHandle>();
            for (FileHandle file : dir.getFilesUnchecked()) {
                if (files.put(file.getName().toLowerCase(Locale.ROOT), file) != null) return Status.UNKNOWN;
            }
            FileHandle sfv = files.get(info.getSFVFileName().toLowerCase(Locale.ROOT));
            if (sfv == null) return Status.UNKNOWN;
            if (AuditFailure.isBad(sfv)) return Status.INCOMPLETE;
            if (sfv.isUploading() || sfv.getSize() == 0 || sfv.getXfertime() == -1) return Status.INCOMPLETE;
            long metadataCrc = sfv.getCheckSumCached();
            if (metadataCrc == 0 || metadataCrc != info.getChecksum() || info.getEntries().isEmpty()) return Status.UNKNOWN;
            boolean unknown = false;
            for (var entry : info.getEntries().entrySet()) {
                FileHandle file = files.get(entry.getKey().toLowerCase(Locale.ROOT));
                if (file == null) return Status.INCOMPLETE;
                if (AuditFailure.isBad(file)) return Status.INCOMPLETE;
                if (file.isUploading() || file.getSize() == 0 || file.getXfertime() == -1) return Status.INCOMPLETE;
                long crc = file.getCheckSumCached();
                if (crc == 0) unknown = true;
                else if (crc != entry.getValue()) return Status.INCOMPLETE;
            }
            return unknown ? Status.UNKNOWN : Status.COMPLETE;
        } catch (FileNotFoundException | KeyNotFoundException e) {
            return Status.UNKNOWN;
        }
    }

    public static Status zip(DirectoryHandle dir) {
        try {
            DizInfo info = dir.getPluginMetaData(DizInfo.DIZINFO);
            if (!info.isValid() || info.getTotal() <= 0) return Status.UNKNOWN;
            int present = 0;
            for (FileHandle file : dir.getFilesUnchecked()) {
                if (!file.getName().toLowerCase(Locale.ROOT).endsWith(".zip")) continue;
                if (AuditFailure.isBad(file)) return Status.INCOMPLETE;
                if (file.isUploading() || file.getSize() == 0 || file.getXfertime() == -1) return Status.INCOMPLETE;
                present++;
            }
            return present == info.getTotal() ? Status.COMPLETE : Status.INCOMPLETE;
        } catch (FileNotFoundException | KeyNotFoundException e) {
            return Status.UNKNOWN;
        }
    }
}
