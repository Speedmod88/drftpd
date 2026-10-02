package org.drftpd.webadmin.master;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class SrrdbRecoveryTest {
    @Test void fullVfsPathUsesOnlyReleaseNameForApiAndKeepsImportDestination() throws Exception {
        String name = "Amphibia.S03E18.FiNAL.FRENCH.WEB.H264-C0MPL3T3D";
        String destination = "/_INCOMPLETED/TV-SD-FRENCH/" + name;
        FakeLibrary target = new FakeLibrary();
        try (var recovery = new SrrdbRecovery(temp.resolve("path-review.json"), 100,
                new SrrdbClient(1024, (uri, limit) -> {
                    assertFalse(uri.getPath().contains("_INCOMPLETED"));
                    assertFalse(uri.getPath().contains("TV-SD-FRENCH"));
                    assertTrue(uri.getPath().contains(name));
                    return uri.getHost().equals("api.srrdb.com") ? SrrdbClientTest.details(name, file) : data;
                }), target)) {
            recovery.scan("siteop", destination + "/", false);
            waitUntil(() -> recovery.view().get("scanState").equals("completed"));
            assertEquals(destination, entries(recovery).get(0).get("releasePath"));
            recovery.decide((String) entries(recovery).get(0).get("id"), "accept", "siteop");
            waitUntil(() -> entries(recovery).get(0).get("state").equals("installed"));
            assertEquals(destination, target.installedPath);
        }
        assertEquals(name, SrrdbRecovery.releaseName(destination + "///"));
    }
    @TempDir Path temp;
    final byte[] data = SrrdbClientTest.bytes("release nfo content");
    final SrrdbClient.RemoteFile file = SrrdbClientTest.file("release.nfo", data);
    final AtomicInteger downloads = new AtomicInteger();
    final FakeLibrary library = new FakeLibrary();

    private SrrdbRecovery open() throws Exception {
        return new SrrdbRecovery(temp.resolve("review.json"), 100, new SrrdbClient(1024, (uri, limit) -> {
            if (uri.getHost().equals("api.srrdb.com")) return SrrdbClientTest.details("Release-GRP", file);
            downloads.incrementAndGet();
            return data;
        }), library);
    }
    private static void waitUntil(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(20);
        assertTrue(condition.getAsBoolean(), "Background operation did not finish");
    }
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> entries(SrrdbRecovery recovery) {
        return (List<Map<String, Object>>) recovery.view().get("files");
    }
    private String scan(SrrdbRecovery recovery) throws Exception {
        recovery.scan("siteop", "/SECTION/Release-GRP", false);
        waitUntil(() -> recovery.view().get("scanState").equals("completed"));
        return (String) entries(recovery).get(0).get("id");
    }

    @Test void lookupDoesNotDownloadAndAcceptIsExactlyOnce() throws Exception {
        try (var recovery = open()) {
            String id = scan(recovery);
            assertEquals(0, downloads.get());
            assertEquals(0, library.installs.get());
            assertEquals("pending", entries(recovery).get(0).get("state"));
            recovery.decide(id, "accept", "siteop");
            assertThrows(IllegalArgumentException.class, () -> recovery.decide(id, "accept", "siteop"));
            waitUntil(() -> entries(recovery).get(0).get("state").equals("installed"));
            assertEquals(1, downloads.get());
            assertEquals(1, library.installs.get());
        }
    }
    @Test void rejectionPersistsAndNeverDownloads() throws Exception {
        String id;
        try (var recovery = open()) {
            id = scan(recovery);
            recovery.decide(id, "reject", "siteop");
        }
        try (var restored = open()) {
            assertEquals(id, entries(restored).get(0).get("id"));
            assertEquals("rejected", entries(restored).get(0).get("state"));
            assertEquals(0, downloads.get());
            restored.clearFinished();
            assertTrue(entries(restored).isEmpty());
        }
    }
    @Test void existingMetadataIsNotOverwrittenOrDownloaded() throws Exception {
        try (var recovery = open()) {
            String id = scan(recovery);
            library.missing = false;
            recovery.decide(id, "accept", "siteop");
            waitUntil(() -> entries(recovery).get(0).get("state").equals("failed"));
            assertEquals(0, downloads.get());
            assertEquals(0, library.installs.get());
        }
    }
    @Test void pendingReviewSurvivesRestartWithoutAutomaticApproval() throws Exception {
        String id;
        try (var recovery = open()) { id = scan(recovery); }
        try (var restored = open()) {
            assertEquals(id, entries(restored).get(0).get("id"));
            assertEquals("pending", entries(restored).get(0).get("state"));
            assertEquals(0, downloads.get());
        }
    }
    @Test void interruptedImportBecomesFailedNotReplayed() throws Exception {
        try (var recovery = open()) { scan(recovery); }
        Path state = temp.resolve("review.json");
        Files.writeString(state, Files.readString(state).replace("\"pending\"", "\"downloading\""));
        try (var restored = open()) {
            assertEquals("failed", entries(restored).get(0).get("state"));
            assertEquals(0, downloads.get());
            assertEquals(0, library.installs.get());
        }
    }
    @Test void repeatedScanDoesNotDuplicatePendingEntry() throws Exception {
        try (var recovery = open()) {
            String id = scan(recovery);
            assertEquals(id, scan(recovery));
            assertEquals(1, entries(recovery).size());
        }
    }
    @Test void invalidPathsAreRejectedBeforeWork() throws Exception {
        try (var recovery = open()) {
            for (String path : List.of("relative", "/a/../b", "/a/./b", "/a\\b", "/a\nb")) {
                assertThrows(IllegalArgumentException.class, () -> recovery.scan("siteop", path, false));
            }
            assertEquals("idle", recovery.view().get("scanState"));
        }
    }
    @Test void corruptStateIsNotOverwritten() throws Exception {
        Path path = temp.resolve("review.json");
        Files.writeString(path, "not-json");
        assertThrows(java.io.IOException.class, this::open);
        assertEquals("not-json", Files.readString(path));
    }

    private static final class FakeLibrary implements SrrdbRecovery.Library {
        volatile boolean missing = true;
        final AtomicInteger installs = new AtomicInteger();
        volatile String installedPath;
        @Override public List<String> releases(String user, String path, boolean recursive, int limit) { return List.of(path); }
        @Override public boolean missing(String user, String path, String file) { return missing; }
        @Override public String install(String user, String path, SrrdbClient.RemoteFile file, byte[] content) {
            assertTrue(missing);
            installedPath = path;
            installs.incrementAndGet();
            return "TestSlave";
        }
    }
}
