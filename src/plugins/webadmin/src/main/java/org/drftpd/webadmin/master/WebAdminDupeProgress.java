package org.drftpd.webadmin.master;

import org.drftpd.common.dynamicdata.KeyNotFoundException;
import org.drftpd.master.vfs.DirectoryHandle;
import org.drftpd.master.vfs.FileHandle;
import org.drftpd.zipscript.common.sfv.SFVInfo;
import org.drftpd.zipscript.common.zip.DizInfo;
import org.drftpd.zipscript.master.audit.AuditFailure;

import java.io.FileNotFoundException;
import java.util.*;
import java.util.regex.Pattern;

/** Snapshot percentages only: never fetch checksums or metadata from slaves. */
final class WebAdminDupeProgress {
    private static final Pattern DISC = Pattern.compile("(?i)(?:CD|DISC|DISK|DVD)[._ -]?([0-9]{1,3})");
    record Progress(Integer percent, long completed, long total) { }
    private static final Progress UNKNOWN = new Progress(null, 0, 0);

    static Progress read(DirectoryHandle dir) { return read(dir, 0, new int[]{128}); }

    private static Progress read(DirectoryHandle dir, int depth, int[] remaining) {
        if (depth > 8 || --remaining[0] < 0) return UNKNOWN;
        try {
            Collection<FileHandle> files = dir.getFilesUnchecked();
            if (files.stream().anyMatch(f -> name(f).endsWith(".sfv"))) return sfv(dir, files);
            try {
                DizInfo info = dir.getPluginMetaData(DizInfo.DIZINFO);
                if (info != null && info.isValid() && info.getTotal() > 0) {
                    long valid = 0, present = 0;
                    for (FileHandle file : files) {
                        if (!name(file).endsWith(".zip")) continue;
                        present++;
                        if (finished(file)) valid++;
                    }
                    return present > info.getTotal() ? UNKNOWN : progress(valid, info.getTotal());
                }
            } catch (KeyNotFoundException ignored) { }
            if (files.stream().anyMatch(f -> name(f).endsWith(".zip"))) return UNKNOWN;
            TreeMap<Integer, DirectoryHandle> discs = new TreeMap<>();
            for (var inode : dir.getInodeHandlesUnchecked()) {
                if (inode.isLink()) return UNKNOWN;
                if (inode instanceof DirectoryHandle child) {
                    var match = DISC.matcher(child.getName());
                    if (!match.matches()) return UNKNOWN;
                    int number = Integer.parseInt(match.group(1));
                    if (number < 1 || discs.put(number, child) != null) return UNKNOWN;
                } else if (!inode.getName().toLowerCase(Locale.ROOT).matches(".*\\.(nfo|diz|txt)")) return UNKNOWN;
            }
            if (discs.isEmpty() || discs.lastKey() != discs.size()) return UNKNOWN;
            long completed = 0, total = 0;
            for (var child : discs.values()) {
                Progress part = read(child, depth + 1, remaining);
                if (part.percent == null) return UNKNOWN;
                completed += part.completed; total += part.total;
            }
            return progress(completed, total);
        } catch (FileNotFoundException | KeyNotFoundException e) {
            return UNKNOWN;
        }
    }

    private static Progress sfv(DirectoryHandle dir, Collection<FileHandle> files)
            throws FileNotFoundException, KeyNotFoundException {
        SFVInfo info = dir.getPluginMetaData(SFVInfo.SFVINFO);
        if (info == null || info.getSFVFileName() == null || info.getEntries() == null || info.getEntries().isEmpty()) return UNKNOWN;
        Map<String, FileHandle> byName = new HashMap<>();
        for (FileHandle file : files) if (byName.put(name(file), file) != null) return UNKNOWN;
        FileHandle sfv = byName.get(info.getSFVFileName().toLowerCase(Locale.ROOT));
        if (sfv == null || !finished(sfv) || sfv.getCheckSumCached() == 0
                || sfv.getCheckSumCached() != info.getChecksum()) return UNKNOWN;
        long completed = 0;
        for (var entry : info.getEntries().entrySet()) {
            FileHandle file = byName.get(entry.getKey().toLowerCase(Locale.ROOT));
            if (file == null || !finished(file)) continue;
            long crc = file.getCheckSumCached();
            if (crc == 0) return UNKNOWN;
            if (crc == entry.getValue()) completed++;
        }
        return progress(completed, info.getEntries().size());
    }

    private static boolean finished(FileHandle file) throws FileNotFoundException {
        return !file.isUploading() && file.getSize() > 0 && file.getXfertime() != -1 && !AuditFailure.isBad(file);
    }

    private static String name(FileHandle file) { return file.getName().toLowerCase(Locale.ROOT); }

    private static Progress progress(long completed, long total) {
        return total <= 0 ? UNKNOWN : new Progress((int) (completed * 100 / total), completed, total);
    }
}
