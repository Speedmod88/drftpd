package org.drftpd.slave;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class LogArchivesTest {
    @TempDir Path root;
    private Path archive(String relative, String text) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        try (var output = new GZIPOutputStream(Files.newOutputStream(file))) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }
    @Test void readsTrueTailOfCompressedArchiveWithBoundedOutput() throws Exception {
        Path file = archive("2026-11/master-2026-11-02.log.gz", "prefix\n" + "old line\n".repeat(100000) + "second\nlast\n");
        long size = Files.size(file);
        String result = LogArchives.read(file, 2, 2 * 1024 * 1024, 30000, bytes -> {});
        assertEquals("second\nlast", result);
        assertEquals(size, Files.size(file));
        try (var files = Files.list(file.getParent())) {
            assertEquals(1, files.count(), "Reading must not extract files");
        }
    }
    @Test void preservesUtf8CrLfAndUnterminatedLastLine() throws Exception {
        Path file = archive("master-2026-11-02.log.gz", "first\r\nsecond\r\nlast");
        assertEquals("second\nlast", LogArchives.read(file, 2, 1000, 30000, bytes -> {}));
    }
    @Test void invalidAndOverLimitArchivesFailWithoutReturningPartialTail() throws Exception {
        Path large = archive("large.log.gz", "x".repeat(10000));
        assertThrows(IOException.class, () -> LogArchives.read(large, 2, 100, 30000, bytes -> {}));
        Path broken = Files.writeString(root.resolve("broken.log.gz"), "not gzip");
        assertThrows(IOException.class, () -> LogArchives.read(broken, 2, 10000, 30000, bytes -> {}));
        Path valid = archive("truncated.log.gz", "some content");
        byte[] bytes = Files.readAllBytes(valid);
        Files.write(valid, java.util.Arrays.copyOf(bytes, bytes.length - 5));
        assertThrows(IOException.class, () -> LogArchives.read(valid, 2, 10000, 30000, n -> {}));
    }
    @Test void listsCurrentAndMonthlyArchivesAndValidatesDates() throws Exception {
        Files.writeString(root.resolve("master.log"), "current");
        archive("2026-11/master-2026-11-02.log.gz", "archive");
        Files.writeString(root.resolve("private.conf"), "secret");
        assertEquals(List.of("2026-11/master-2026-11-02.log.gz", "master.log"), LogArchives.list(root));
        assertEquals("2026-11-02", LogArchives.date("2026-11/master-2026-11-02.log.gz"));
        assertEquals("", LogArchives.date("master-2026-02-30.log.gz"));
        assertTrue(LogArchives.archived("master.log.gz"));
        assertFalse(LogArchives.archived("master.log"));
    }
    @Test void refusesTraversalAbsolutePathsAndNonLogs() throws Exception {
        for (String path : List.of("../private.log.gz", "/a.log.gz", "2026-11/../../private.log.gz", "a\\file.log.gz",
                "2026-11/a.conf", "C:a.log.gz", "file.log.gz\n", "./file.log.gz")) {
            assertFalse(LogArchives.validPath(path), path);
            assertThrows(IOException.class, () -> LogArchives.safeFile(root, path));
        }
    }
    @Test void backgroundJobsAreOwnerScopedDeduplicatedAndDoNotLeakPartialResults() throws Exception {
        archive("2026-11/master-2026-11-02.log.gz", "first\nlast\n");
        try (var reader = new LogArchives("test archive")) {
            var initial = reader.start("siteop", root, "2026-11/master-2026-11-02.log.gz", 1);
            assertEquals(initial.job(), reader.start("siteop", root, initial.file(), 1).job());
            assertThrows(IOException.class, () -> reader.status("other", initial.job()));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            var current = initial;
            while (!current.state().equals("completed") && !current.state().equals("failed") && System.nanoTime() < deadline) {
                assertEquals("", current.content());
                Thread.sleep(10);
                current = reader.status("siteop", initial.job());
            }
            assertEquals("completed", current.state());
            assertEquals("last", current.content());
        }
    }

    @Test void refusesSymlinkedMonthlyDirectories() throws Exception {
        Path outside = Files.createDirectory(root.resolve("outside"));
        Files.writeString(outside.resolve("master-2026-11-02.log.gz"), "private");
        try { Files.createSymbolicLink(root.resolve("2026-11"), outside); }
        catch (IOException | UnsupportedOperationException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "Symlink creation unavailable on this host");
        }
        assertThrows(IOException.class, () -> LogArchives.safeFile(root, "2026-11/master-2026-11-02.log.gz"));
        assertFalse(LogArchives.list(root).contains("2026-11/master-2026-11-02.log.gz"));
    }
}
