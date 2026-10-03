package org.drftpd.zipscript.master.audit;

/** Expected and measured checksums are intentionally distinct, including valid CRC zero. */
record AuditResult(String slave, String path, long size, long modified, long expected,
                   long measured, String source, long checkedAt, boolean mismatch) {
    boolean sameFile(long fileSize, long fileModified) { return size == fileSize && modified == fileModified; }

    static AuditResult measured(String slave, String path, long size, long modified, Long reference,
                                String source, long measured, long now) {
        return new AuditResult(slave, path, size, modified, reference == null ? measured : reference,
                measured, reference == null ? "baseline" : source, now,
                reference != null && reference.longValue() != measured);
    }

    boolean newMismatch(AuditResult previous) {
        return mismatch && (previous == null || !previous.mismatch || !sameFile(previous.size, previous.modified)
                || measured != previous.measured || expected != previous.expected);
    }
}
