package org.drftpd.slave;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.zip.CRC32;

/** Small, compare-before-replace metadata updates with private, durable backups. */
public final class MetadataReplacement {
    public static final String PROBE = "metadata-replace-v1";
    public static final int CAPABILITY = -2147482999;
    public static final int MAX_BYTES = 1024 * 1024;

    private MetadataReplacement() { }

    public static long crc(byte[] bytes) {
        CRC32 crc = new CRC32(); crc.update(bytes); return crc.getValue();
    }

    public static long replace(Path target, Path backups, List<Path> roots, String transaction,
                               long expectedSize, long expectedCrc, byte[] replacement, long replacementCrc)
            throws IOException {
        UUID.fromString(transaction);
        String name = target.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (expectedSize < 0 || expectedSize > MAX_BYTES
                || (!name.endsWith(".sfv") && !name.endsWith(".nfo")) || replacement.length == 0
                || replacement.length > MAX_BYTES || crc(replacement) != replacementCrc) {
            throw new IOException("Invalid replacement metadata");
        }
        target = target.toAbsolutePath().normalize();
        for (Path current = target; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current)) throw new IOException("Symlink metadata path refused");
        }
        BasicFileAttributes before = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.size() > MAX_BYTES) throw new IOException("Invalid metadata target");
        byte[] original = readSmall(target);
        for (Path current = backups.toAbsolutePath().normalize(); current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current)) throw new IOException("Symlink backup path refused");
        }
        if (Files.getFileStore(target).supportsFileAttributeView("posix")) {
            Files.createDirectories(backups, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));
        } else {
            Files.createDirectories(backups);
        }
        Path privateRoot = backups.toRealPath();
        for (Path root : roots) {
            if (privateRoot.startsWith(root.toRealPath())) throw new IOException("Backup directory must be outside FTP roots");
        }
        Path backup = privateRoot.resolve(transaction + ".original");
        Path receipt = privateRoot.resolve(transaction + ".target");
        String identity = target + "\n" + expectedSize + "\n" + expectedCrc + "\n" + replacementCrc;
        // A lost reply may be retried. Never replace the original backup on retry.
        if (Files.exists(receipt, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(receipt) || Files.isSymbolicLink(backup)
                    || !Files.readString(receipt).equals(identity) || !Files.isRegularFile(backup)
                    || Files.size(backup) != expectedSize || crc(readSmall(backup)) != expectedCrc) {
                throw new IOException("Replacement transaction does not match its private backup");
            }
            if (Arrays.equals(original, replacement)) return replacementCrc;
        }
        if (original.length != expectedSize || crc(original) != expectedCrc) {
            throw new IOException("Local metadata changed since approval");
        }
        if (!Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) durableWrite(backup, original);
        else if (Files.isSymbolicLink(backup) || !Arrays.equals(readSmall(backup), original)) {
            throw new IOException("Backup conflict");
        }
        if (!Files.exists(receipt, LinkOption.NOFOLLOW_LINKS)) {
            durableWrite(receipt, identity.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        Path staged = target.resolveSibling(".drftpd-recovery-" + transaction + ".tmp");
        boolean stagedOwned = false;
        try {
            // Never overwrite or remove a staging file left by another attempt.
            Files.copy(target, staged, StandardCopyOption.COPY_ATTRIBUTES);
            stagedOwned = true;
            try (FileChannel channel = FileChannel.open(staged, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer data = ByteBuffer.wrap(replacement);
                while (data.hasRemaining()) channel.write(data);
                channel.force(true);
            }
            BasicFileAttributes after = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!before.lastModifiedTime().equals(after.lastModifiedTime()) || before.size() != after.size()
                    || !java.util.Objects.equals(before.fileKey(), after.fileKey())
                    || !Arrays.equals(readSmall(target), original)) {
                throw new IOException("Local metadata changed before commit");
            }
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            if (crc(readSmall(target)) != replacementCrc) throw new IOException("Replacement verification failed; private backup retained");
            return replacementCrc;
        } finally { if (stagedOwned) Files.deleteIfExists(staged); }
    }

    private static void durableWrite(Path path, byte[] bytes) throws IOException {
        var permissions = Files.getFileStore(path.getParent()).supportsFileAttributeView("posix")
                ? new java.nio.file.attribute.FileAttribute<?>[]{java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))}
                : new java.nio.file.attribute.FileAttribute<?>[0];
        try (FileChannel channel = FileChannel.open(path,
                java.util.Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), permissions)) {
            ByteBuffer data = ByteBuffer.wrap(bytes);
            while (data.hasRemaining()) channel.write(data);
            channel.force(true);
        }
    }

    private static byte[] readSmall(Path path) throws IOException {
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("Metadata exceeds replacement size limit");
            return bytes;
        }
    }
}
