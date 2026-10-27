package org.drftpd.slave;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class MetadataReplacementTest {
    @TempDir Path temp;
    private final byte[] original = {1, 2, 3};
    private final byte[] replacement = {4, 5, 6, 7};

    private long replace(Path file, Path backup, String id) throws IOException {
        return MetadataReplacement.replace(file, backup, List.of(temp.resolve("site")), id,
                original.length, MetadataReplacement.crc(original), replacement,
                MetadataReplacement.crc(replacement));
    }

    private Path target(String name) throws IOException {
        Files.createDirectories(temp.resolve("site"));
        return Files.write(temp.resolve("site").resolve(name), original);
    }

    @Test void backupAndLostReplyRetryPreserveOriginal() throws Exception {
        Path file = target("release.sfv");
        Path backup = temp.resolve("private");
        String id = UUID.randomUUID().toString();
        assertEquals(MetadataReplacement.crc(replacement), replace(file, backup, id));
        assertArrayEquals(replacement, Files.readAllBytes(file));
        assertArrayEquals(original, Files.readAllBytes(backup.resolve(id + ".original")));
        assertEquals(MetadataReplacement.crc(replacement), replace(file, backup, id));
        assertArrayEquals(original, Files.readAllBytes(backup.resolve(id + ".original")));
    }

    @Test void changedTargetIsNotOverwritten() throws Exception {
        Path file = target("release.nfo");
        byte[] changed = {8, 9};
        Files.write(file, changed);
        assertThrows(IOException.class, () -> replace(file, temp.resolve("private"), UUID.randomUUID().toString()));
        assertArrayEquals(changed, Files.readAllBytes(file));
    }

    @Test void backupInsideFtpRootIsRefused() throws Exception {
        Path file = target("release.nfo");
        assertThrows(IOException.class, () -> replace(file, temp.resolve("site/backups"), UUID.randomUUID().toString()));
        assertArrayEquals(original, Files.readAllBytes(file));
    }

    @Test void payloadFilesCannotBeReplaced() throws Exception {
        Path file = target("release.rar");
        assertThrows(IOException.class, () -> replace(file, temp.resolve("private"), UUID.randomUUID().toString()));
        assertArrayEquals(original, Files.readAllBytes(file));
    }

    @Test void stagingCollisionDoesNotDeleteExistingFile() throws Exception {
        Path file = target("release.nfo");
        String id = UUID.randomUUID().toString();
        Path staging = file.resolveSibling(".drftpd-recovery-" + id + ".tmp");
        byte[] unrelated = {9};
        Files.write(staging, unrelated);
        assertThrows(IOException.class, () -> replace(file, temp.resolve("private"), id));
        assertArrayEquals(unrelated, Files.readAllBytes(staging));
        assertArrayEquals(original, Files.readAllBytes(file));
    }

    @Test void corruptBackupBlocksRetry() throws Exception {
        Path file = target("release.sfv");
        Path backup = temp.resolve("private");
        String id = UUID.randomUUID().toString();
        replace(file, backup, id);
        Files.write(backup.resolve(id + ".original"), new byte[]{0});
        assertThrows(IOException.class, () -> replace(file, backup, id));
        assertArrayEquals(replacement, Files.readAllBytes(file));
    }

    @Test void wrongDownloadedCrcDoesNotChangeTarget() throws Exception {
        Path file = target("release.sfv");
        assertThrows(IOException.class, () -> MetadataReplacement.replace(file, temp.resolve("private"),
                List.of(temp.resolve("site")), UUID.randomUUID().toString(), original.length,
                MetadataReplacement.crc(original), replacement, 123));
        assertArrayEquals(original, Files.readAllBytes(file));
    }
}
