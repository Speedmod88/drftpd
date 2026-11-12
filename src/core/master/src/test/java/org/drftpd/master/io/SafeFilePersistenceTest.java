package org.drftpd.master.io;

import com.cedarsoftware.util.io.JsonReader;
import com.cedarsoftware.util.io.JsonWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class SafeFilePersistenceTest {
    @TempDir Path directory;

    private long temporaryCount() throws IOException {
        try (var files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".tmp")).count();
        }
    }

    @Test void successfulSavePreservesOriginalUntilCallbackAndCloseComplete() throws Exception {
        Path target = directory.resolve("user.json");
        Files.writeString(target, "old");
        SafeFileOutputStream.writeAtomically(target.toFile(), output -> {
            output.write("new".getBytes(StandardCharsets.UTF_8));
            output.close();
            assertEquals("old", Files.readString(target));
        });
        assertEquals("new", Files.readString(target));
        assertEquals(0, temporaryCount());
    }

    @Test void serializationExceptionsNeverPromotePartialDataAndCleanOnlyOwnedTemporary() throws Exception {
        Path target = directory.resolve("user.json");
        Path historical = directory.resolve("historical.json123.tmp");
        Files.writeString(target, "old");
        Files.writeString(historical, "keep for manual review");
        for (boolean checked : new boolean[]{true, false}) {
            Exception failure = assertThrows(Exception.class, () -> SafeFileOutputStream.writeAtomically(target.toFile(), output -> {
                output.write("partial".getBytes(StandardCharsets.UTF_8));
                output.close();
                if (checked) throw new IOException("serialization failed");
                throw new IllegalStateException("serialization failed");
            }));
            assertEquals("serialization failed", failure.getMessage());
            assertEquals("old", Files.readString(target));
            assertEquals(1, temporaryCount());
            assertEquals("keep for manual review", Files.readString(historical));
        }
    }

    @Test void abortAndRepeatedCloseNeverReplaceExistingFile() throws Exception {
        Path target = directory.resolve("x");
        Files.writeString(target, "old");
        SafeFileOutputStream stream = new SafeFileOutputStream(target.toFile());
        stream.write("partial".getBytes(StandardCharsets.UTF_8));
        stream.abort();
        stream.close();
        assertThrows(IOException.class, () -> stream.write(1));
        assertEquals("old", Files.readString(target));
        assertEquals(0, temporaryCount());
    }

    @Test void untouchedBinaryStreamDoesNotLeaveEmptyTemporary() throws Exception {
        Path target = directory.resolve("x");
        Files.writeString(target, "old");
        try (var ignored = new SafeFileOutputStream(target.toFile())) { }
        assertEquals("old", Files.readString(target));
        assertEquals(0, temporaryCount());
    }

    @Test void failedUnderlyingWriteIsStickyAndDiscarded() throws Exception {
        Path target = directory.resolve("user.json");
        Files.writeString(target, "old");
        try (var output = new SafeFileOutputStream(target.toFile())) {
            output.write(1);
            var field = SafeFileOutputStream.class.getDeclaredField("output");
            field.setAccessible(true);
            ((java.io.FileOutputStream) field.get(output)).close();
            assertThrows(IOException.class, () -> output.write(2));
            assertThrows(IOException.class, () -> output.write(3));
        }
        assertEquals("old", Files.readString(target));
        assertEquals(0, temporaryCount());
    }

    @Test void serializationErrorAlsoAbortsWithoutLeavingPartialData() throws Exception {
        Path target = directory.resolve("user.json");
        Files.writeString(target, "old");
        assertThrows(AssertionError.class, () -> SafeFileOutputStream.writeAtomically(target.toFile(), output -> {
            output.write(1);
            throw new AssertionError("serializer failed");
        }));
        assertEquals("old", Files.readString(target));
        assertEquals(0, temporaryCount());
    }

    @Test void failedReplacementKeepsCompletedRecoveryFileAndOriginalDirectory() throws Exception {
        Path target = directory.resolve("occupied");
        Files.createDirectory(target);
        Files.writeString(target.resolve("sentinel"), "original");
        assertThrows(IOException.class, () -> SafeFileOutputStream.writeAtomically(target.toFile(), output ->
                output.write("complete recovery data".getBytes(StandardCharsets.UTF_8))));
        assertEquals("original", Files.readString(target.resolve("sentinel")));
        assertEquals(1, temporaryCount());
        try (var files = Files.list(directory)) {
            Path recovery = files.filter(path -> path.toString().endsWith(".tmp")).findFirst().orElseThrow();
            assertEquals("complete recovery data", Files.readString(recovery));
        }
        Path healthy = directory.resolve("healthy.json");
        SafeFileOutputStream.writeAtomically(healthy.toFile(), output -> output.write(1));
        assertEquals(1, Files.size(healthy));
    }

    @Test void constructorFailureDoesNotStrandSubsequentSaves() throws Exception {
        Path parent = directory.resolve("missing");
        Path target = parent.resolve("user.json");
        assertThrows(IOException.class, () -> new SafeFileOutputStream(target.toFile()));
        Files.createDirectory(parent);
        assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                SafeFileOutputStream.writeAtomically(target.toFile(), output -> output.write(1)));
    }

    @Test void textWriterPreservesUtf8AndEmptyFileBehavior() throws Exception {
        Path target = directory.resolve("x");
        try (var writer = new SafeFileWriter(target.toFile())) { writer.write("NFO \u00e9"); }
        assertEquals("NFO \u00e9", Files.readString(target));
        try (var writer = new SafeFileWriter(target.toString())) { writer.write(""); }
        assertEquals(0, Files.size(target));
        assertEquals(0, temporaryCount());
        var lockRegistry = SafeFileOutputStream.class.getDeclaredField("locks");
        lockRegistry.setAccessible(true);
        assertTrue(((Map<?, ?>) lockRegistry.get(null)).isEmpty());
    }

    @Test void jsonWriterClosesWithoutEarlyCommitAndRemainsReadable() throws Exception {
        Path target = directory.resolve("user.json");
        Files.writeString(target, "old");
        SafeFileOutputStream.writeAtomically(target.toFile(), output -> {
            try (JsonWriter writer = new JsonWriter(output)) { writer.write(Map.of("credits", 123L)); }
            assertEquals("old", Files.readString(target));
        });
        try (var input = Files.newInputStream(target); JsonReader reader = new JsonReader(input)) {
            assertEquals(123L, ((Map<?, ?>) reader.readObject()).get("credits"));
        }
        assertEquals(0, temporaryCount());
    }

    @Test void concurrentSavesReplaceWholeFilesAndLeaveNoTemporaries() throws Exception {
        Path target = directory.resolve("user.json");
        Files.writeString(target, "{\"value\":-1}");
        ExecutorService executor = Executors.newFixedThreadPool(6);
        var futures = new ArrayList<Future<?>>();
        try {
            for (int i = 0; i < 80; i++) {
                int number = i;
                futures.add(executor.submit(() -> {
                    try {
                        Path normalizedAlias = directory.resolve("unused/../user.json");
                        SafeFileOutputStream.writeAtomically(normalizedAlias.toFile(), output -> {
                            byte[] bytes = ("{\"value\":" + number + "}").getBytes(StandardCharsets.UTF_8);
                            for (byte value : bytes) output.write(value);
                        });
                    } catch (IOException error) { throw new RuntimeException(error); }
                }));
            }
            for (Future<?> future : futures) future.get(10, TimeUnit.SECONDS);
            assertTrue(Files.readString(target).matches("\\{\"value\":-?[0-9]+}"));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals(0, temporaryCount());
    }

    @Test void openReaderSeesOriginalOrCausesSafeReplacementFailure() throws Exception {
        Path target = directory.resolve("reader.json");
        Files.writeString(target, "original");
        boolean blocked = false;
        try (var reader = Files.newInputStream(target)) {
            try {
                SafeFileOutputStream.writeAtomically(target.toFile(), output ->
                        output.write("replacement".getBytes(StandardCharsets.UTF_8)));
            } catch (java.nio.file.AccessDeniedException expectedOnWindows) { blocked = true; }
            assertEquals("original", new String(reader.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertEquals(blocked ? "original" : "replacement", Files.readString(target));
        assertEquals(blocked ? 1 : 0, temporaryCount());
        SafeFileOutputStream.writeAtomically(target.toFile(), output ->
                output.write("replacement".getBytes(StandardCharsets.UTF_8)));
        assertEquals("replacement", Files.readString(target));
    }

    @Test void blockedSerializationDoesNotHoldFilesystemLocksOrBlockUnrelatedFiles() throws Exception {
        Path target = directory.resolve("slave.json");
        Files.writeString(target, "old");
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(3);
        try {
            Future<?> first = executor.submit(() -> {
                try {
                    SafeFileOutputStream.writeAtomically(target.toFile(), output -> {
                        output.write("first".getBytes(StandardCharsets.UTF_8));
                        started.countDown();
                        try { if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("test timed out"); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
                    });
                } catch (IOException e) { throw new RuntimeException(e); }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            executor.submit(() -> {
                try {
                    SafeFileOutputStream.writeAtomically(target.toFile(), output -> output.write("second".getBytes(StandardCharsets.UTF_8)));
                    SafeFileOutputStream.writeAtomically(directory.resolve("other.json").toFile(), output -> output.write(1));
                } catch (IOException e) { throw new RuntimeException(e); }
            }).get(5, TimeUnit.SECONDS);
            assertEquals("second", Files.readString(target));
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            assertEquals("first", Files.readString(target));
        } finally {
            release.countDown(); executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals(0, temporaryCount());
    }
}
