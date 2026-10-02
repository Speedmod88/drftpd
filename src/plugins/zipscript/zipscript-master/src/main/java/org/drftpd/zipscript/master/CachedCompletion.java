package org.drftpd.zipscript.master;

import org.drftpd.common.dynamicdata.KeyNotFoundException;
import org.drftpd.master.vfs.DirectoryHandle;
import org.drftpd.master.vfs.FileHandle;
import org.drftpd.zipscript.common.sfv.SFVInfo;
import org.drftpd.zipscript.common.zip.DizInfo;

import java.io.FileNotFoundException;
import java.util.Locale;

/** Read-only VFS checks: never fetch metadata or checksums from a slave. */
public final class CachedCompletion {
    public enum Status { COMPLETE, INCOMPLETE, UNKNOWN }

    private CachedCompletion() { }

    public static Status release(DirectoryHandle dir) {
        try {
            for (FileHandle file : dir.getFilesUnchecked()) {
                if (file.getName().toLowerCase(Locale.ROOT).endsWith(".sfv")) return sfv(dir);
            }
            return zip(dir);
        } catch (FileNotFoundException e) {
            return Status.UNKNOWN;
        }
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
            if (sfv.isUploading() || sfv.getSize() == 0 || sfv.getXfertime() == -1) return Status.INCOMPLETE;
            long metadataCrc = sfv.getCheckSumCached();
            if (metadataCrc == 0 || metadataCrc != info.getChecksum() || info.getEntries().isEmpty()) return Status.UNKNOWN;
            boolean unknown = false;
            for (var entry : info.getEntries().entrySet()) {
                FileHandle file = files.get(entry.getKey().toLowerCase(Locale.ROOT));
                if (file == null) return Status.INCOMPLETE;
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
                if (file.isUploading() || file.getSize() == 0 || file.getXfertime() == -1) return Status.INCOMPLETE;
                present++;
            }
            return present == info.getTotal() ? Status.COMPLETE : Status.INCOMPLETE;
        } catch (FileNotFoundException | KeyNotFoundException e) {
            return Status.UNKNOWN;
        }
    }
}
