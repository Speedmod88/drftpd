package org.drftpd.slave;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.zip.CRC32;

/** One resumable audit, separate from all transfer and explicit checksum code. */
public final class IdleChecksumScanner {
    public static final long MORE = -1, BUSY = -2;
    public static final int CAPABILITY = -2147483000;
    private Path path;
    private BasicFileAttributes original;
    private long offset, activity;
    private CRC32 crc;

    public synchronized long step(Path requested, long expectedSize, int budget,
                                  BooleanSupplier idle, LongSupplier activitySequence) throws IOException {
        if (!idle.getAsBoolean() || Thread.currentThread().isInterrupted()) return BUSY;
        BasicFileAttributes attributes = Files.readAttributes(requested, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.size() != expectedSize) {
            clear();
            throw new IOException("Audit target changed or is not a regular file");
        }
        long sequence = activitySequence.getAsLong();
        if (!requested.equals(path) || sequence != activity || !same(attributes, original)) {
            path = requested;
            original = attributes;
            offset = 0;
            activity = sequence;
            crc = new CRC32();
        }
        // Close the descriptor between steps: idle audits must not exhaust file handles.
        try (FileChannel channel = FileChannel.open(requested, StandardOpenOption.READ)) {
            channel.position(offset);
            ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
            int remaining = Math.max(1, Math.min(budget, 4 * 1024 * 1024));
            while (remaining > 0 && offset < expectedSize) {
                if (!idle.getAsBoolean() || activity != activitySequence.getAsLong()
                        || Thread.currentThread().isInterrupted()) return BUSY;
                buffer.clear();
                buffer.limit((int) Math.min(buffer.capacity(), Math.min(remaining, expectedSize - offset)));
                int read = channel.read(buffer);
                if (read < 0) throw new IOException("Audit target truncated while reading");
                if (read == 0) return MORE;
                crc.update(buffer.array(), 0, read);
                offset += read;
                remaining -= read;
            }
        } catch (IOException e) {
            clear();
            throw e;
        }
        BasicFileAttributes after = Files.readAttributes(requested, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!same(after, original) || activity != activitySequence.getAsLong()) {
            clear();
            return BUSY;
        }
        if (offset < expectedSize) return MORE;
        long result = crc.getValue();
        clear();
        return result;
    }

    private static boolean same(BasicFileAttributes a, BasicFileAttributes b) {
        return b != null && a.size() == b.size() && a.lastModifiedTime().equals(b.lastModifiedTime())
                && Objects.equals(a.fileKey(), b.fileKey());
    }

    private void clear() { path = null; original = null; crc = null; offset = 0; }
}
