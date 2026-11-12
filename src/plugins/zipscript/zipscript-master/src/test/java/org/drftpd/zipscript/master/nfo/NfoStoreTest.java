package org.drftpd.zipscript.master.nfo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class NfoStoreTest {
    @TempDir Path directory;
    private NfoDisplay.Key key(String path) { return new NfoDisplay.Key(path, 1, 100, 2, 0); }

    @Test void compressedTextSurvivesRestartAndChecksFullIdentity() throws Exception {
        var key = key("/SECTION/Release-GRP/release.nfo");
        String text = "NFO \u00e9\n".repeat(1000);
        try (var store = new NfoStore(directory)) {
            store.put(key, text);
            assertEquals(text, store.get(key));
            assertNull(store.get(new NfoDisplay.Key(key.path(), 1, 100, 3, 0)));
            store.cursor("prefetch", key.path());
        }
        try (var store = new NfoStore(directory)) {
            assertEquals(text, store.get(key));
            assertEquals(key.path(), store.cursor("prefetch"));
        }
    }

    @Test void deletionRemovesOnlyExactReleaseAndDescendantsDurably() throws Exception {
        var first = key("/SECTION/Release-GRP/release.nfo");
        var disc = key("/SECTION/Release-GRP/CD1/disc.nfo");
        var neighbor = key("/SECTION/Release-GRP2/release.nfo");
        try (var store = new NfoStore(directory)) {
            store.put(first, "first"); store.put(disc, "disc"); store.put(neighbor, "neighbor");
            store.removeTree("/SECTION/Release-GRP");
            assertNull(store.get(first)); assertNull(store.get(disc));
            assertEquals("neighbor", store.get(neighbor));
        }
        try (var store = new NfoStore(directory)) {
            assertNull(store.get(first)); assertNull(store.get(disc));
            assertEquals("neighbor", store.get(neighbor));
            store.removeTree(neighbor.path());
            assertNull(store.get(neighbor));
        }
    }

    @Test void updatingPathReplacesOldVersionAndPagingIsBounded() throws Exception {
        var a = key("/A/a.nfo"); var b = key("/B/b.nfo");
        try (var store = new NfoStore(directory)) {
            store.put(b, "b"); store.put(a, "old");
            var newer = new NfoDisplay.Key(a.path(), 1, 100, 4, 0);
            store.put(newer, "new");
            store.cursor("prune", "ignored metadata");
            assertNull(store.get(a));
            assertEquals(List.of(newer), store.page("", 1));
            assertEquals(List.of(b), store.page(newer.path(), 1));
            assertTrue(store.page(b.path(), 1).isEmpty());
            store.removeTree("/");
            assertNull(store.get(newer)); assertNull(store.get(b));
        }
    }

    @Test void rejectsOversizedNfoText() throws Exception {
        try (var store = new NfoStore(directory)) {
            assertThrows(java.io.IOException.class, () -> store.put(key("/big.nfo"), "x".repeat(65537)));
        }
    }
}
