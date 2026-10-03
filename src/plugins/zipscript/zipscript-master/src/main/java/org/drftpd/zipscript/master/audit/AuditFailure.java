package org.drftpd.zipscript.master.audit;

import org.drftpd.common.dynamicdata.Key;
import org.drftpd.common.dynamicdata.KeyNotFoundException;
import org.drftpd.master.vfs.FileHandle;
import java.io.FileNotFoundException;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/** Protects duplicate keepers/deletion from known corruption on any current slave copy. */
public final class AuditFailure implements Serializable {
    private static final long serialVersionUID = 1L;
    public static final Key<AuditFailure> KEY = new Key<>(AuditFailure.class, "badCopies");
    private final long size, modified;
    private final Map<String, Long> copies;
    private AuditFailure(long size, long modified, Map<String, Long> copies) {
        this.size = size; this.modified = modified; this.copies = Map.copyOf(copies);
    }
    public static boolean isBad(FileHandle file) throws FileNotFoundException {
        AuditFailure failure = read(file);
        if (failure == null || failure.size != file.getSize() || failure.modified != file.lastModified()) return false;
        for (var slave : file.getSlaves()) if (failure.copies.containsKey(slave.getName())) return true;
        return false;
    }
    static void record(FileHandle file, String slave, long measured, boolean bad) throws FileNotFoundException {
        AuditFailure old = read(file);
        Map<String, Long> copies = old != null && old.size == file.getSize() && old.modified == file.lastModified()
                ? new HashMap<>(old.copies) : new HashMap<>();
        if (bad) copies.put(slave, measured); else copies.remove(slave);
        if (old == null && copies.isEmpty()) return;
        if (old != null && old.size == file.getSize() && old.modified == file.lastModified()
                && old.copies.equals(copies)) return;
        file.addPluginMetaData(KEY, new AuditFailure(file.getSize(), file.lastModified(), copies));
    }
    private static AuditFailure read(FileHandle file) throws FileNotFoundException {
        try { return file.getPluginMetaData(KEY); } catch (KeyNotFoundException e) { return null; }
    }
}
