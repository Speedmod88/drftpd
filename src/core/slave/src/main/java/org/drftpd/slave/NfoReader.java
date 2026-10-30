package org.drftpd.slave;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;

/** Bounded read of one completed NFO; never reads payloads or calculates CRCs. */
public final class NfoReader {
    public static final String PROBE = "cwd-nfo-text-v1";
    public static final int CAPABILITY = -24004;
    public static final int MAX_BYTES = 65536;
    private NfoReader() { }

    public static String read(Path file, long expectedSize, java.util.Collection<Path> roots) throws IOException {
        if (!file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".nfo")
                || expectedSize < 1 || expectedSize > MAX_BYTES)
            throw new IOException("Invalid NFO size or filename");
        BasicFileAttributes before = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.size() != expectedSize)
            throw new IOException("NFO unavailable or changed");
        Path actual = file.toRealPath();
        boolean insideRoot = false;
        for (Path root : roots) insideRoot |= actual.startsWith(root.toRealPath());
        if (!insideRoot) throw new IOException("NFO is outside configured roots");
        byte[] bytes;
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(MAX_BYTES + 1);
        }
        BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (bytes.length != expectedSize || !after.isRegularFile() || after.size() != before.size()
                || !after.lastModifiedTime().equals(before.lastModifiedTime())
                || !java.util.Objects.equals(before.fileKey(), after.fileKey()))
            throw new IOException("NFO changed while reading");
        // Classic scene NFOs use CP437. UTF-8 files are accepted when their byte stream is valid.
        try {
            return java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            return new String(bytes, Charset.forName("IBM437"));
        }
    }
}
