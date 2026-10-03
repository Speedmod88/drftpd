package org.drftpd.zipscript.master.audit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class AuditStoreTest {
    @TempDir Path temp;
    private record Cursor(String after, long nextPass) { }

    @Test void recordsAndCursorSurviveRestartIncludingZeroCrc() throws Exception {
        AuditResult result = AuditResult.measured("Evo", "/release/file", 42, 123, 0L, "sfv", 0, 1000);
        try (AuditStore store = new AuditStore(temp)) {
            assertNull(store.get("missing", AuditResult.class));
            store.put("file", result);
            store.put("cursor:Evo", new Cursor("/release/file", 0));
            store.commit();
            assertEquals(result, store.get("file", AuditResult.class));
        }
        try (AuditStore store = new AuditStore(temp)) {
            assertEquals(result, store.get("file", AuditResult.class));
            assertEquals("/release/file", store.get("cursor:Evo", Cursor.class).after());
            store.put("cursor:Evo", new Cursor("/other/file", 0));
            assertEquals(result, store.get("file", AuditResult.class));
        }
    }

    @Test void closeCommitsLatestRecords() throws Exception {
        try (AuditStore store = new AuditStore(temp)) { store.put("cursor", new Cursor("/last", 300)); }
        try (AuditStore store = new AuditStore(temp)) {
            assertEquals(new Cursor("/last", 300), store.get("cursor", Cursor.class));
        }
    }

    @Test void repeatMismatchDoesNotAnnounceAgainButNewCorruptionDoes() {
        AuditResult bad = AuditResult.measured("Evo", "/x", 42, 123, 17L, "sfv", 18, 1000);
        assertTrue(bad.newMismatch(null));
        assertFalse(bad.newMismatch(bad));
        AuditResult good = AuditResult.measured("Evo", "/x", 42, 123, 17L, "sfv", 17, 2000);
        assertFalse(good.newMismatch(bad));
        assertTrue(bad.newMismatch(good));
        assertTrue(AuditResult.measured("Evo", "/x", 42, 123, 17L, "sfv", 19, 2000).newMismatch(bad));
    }

    @Test void missingExpectedChecksumEstablishesBaselineNotVerification() {
        AuditResult baseline = AuditResult.measured("Evo", "/x", 42, 123, null, "vfs-cache", 0, 1000);
        assertEquals("baseline", baseline.source());
        assertFalse(baseline.mismatch());
        assertEquals(0, baseline.expected());
        assertTrue(AuditResult.measured("Evo", "/x", 42, 123, baseline.expected(),
                baseline.source(), 12, 2000).mismatch());
    }
}
