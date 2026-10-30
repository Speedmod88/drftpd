package org.drftpd.slave;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class NfoReaderTest {
    @TempDir Path root;

    @Test void readsSmallUtf8NfoWithoutPayloadChanges() throws Exception {
        Path file = Files.write(root.resolve("release.nfo"), "Release information".getBytes(StandardCharsets.UTF_8));
        assertEquals("Release information", NfoReader.read(file, Files.size(file), List.of(root)));
    }
    @Test void supportsClassicCp437Artwork() throws Exception {
        Path file = Files.write(root.resolve("release.nfo"), new byte[]{(byte) 0xb3, 'N', 'F', 'O'});
        assertEquals("\u2502NFO", NfoReader.read(file, 4, List.of(root)));
    }
    @Test void refusesPayloadsOversizeAndChangedSize() throws Exception {
        Path payload = Files.write(root.resolve("file.rar"), new byte[]{1});
        assertThrows(IOException.class, () -> NfoReader.read(payload, 1, List.of(root)));
        Path nfo = Files.write(root.resolve("release.nfo"), new byte[]{1});
        assertThrows(IOException.class, () -> NfoReader.read(nfo, 2, List.of(root)));
        assertThrows(IOException.class, () -> NfoReader.read(nfo, NfoReader.MAX_BYTES + 1L, List.of(root)));
    }
    @Test void refusesFilesOutsideConfiguredRoots() throws Exception {
        Path nfo = Files.write(root.resolve("release.nfo"), new byte[]{1});
        Path other = Files.createDirectory(root.resolve("other"));
        assertThrows(IOException.class, () -> NfoReader.read(nfo, 1, List.of(other)));
    }
}
