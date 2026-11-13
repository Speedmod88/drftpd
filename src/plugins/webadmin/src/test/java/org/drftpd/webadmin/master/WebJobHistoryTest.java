package org.drftpd.webadmin.master;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WebJobHistoryTest {
    @TempDir Path root;
    @Test void savesDupeCompletionPercentagesAcrossRestart() throws Exception {
        String id = UUID.randomUUID().toString();
        var row = new WebAdminDupe.Row("/A/Release-GRP", "release", "NOT COMPLETED", 120, false, 50, 1, 2);
        new WebJobHistory(root).save("alice", Map.of("id", id, "created", 123L,
                "command", "DUPE2", "state", "completed", "dupeResults", java.util.List.of(row)));
        var saved = new WebJobHistory(root).read("alice", id).getAsJsonArray("dupeResults").get(0).getAsJsonObject();
        assertEquals(50, saved.get("completionPercent").getAsInt());
        assertEquals(1, saved.get("completedFiles").getAsLong());
        assertEquals("NOT COMPLETED", saved.get("status").getAsString());
    }
    @Test void persistsAcrossInstancesAndIsolatesAccounts() throws Exception {
        String id = UUID.randomUUID().toString();
        new WebJobHistory(root).save("alice", Map.of("id", id, "created", 123L,
                "command", "DUPE2", "state", "completed", "output", java.util.List.of("result")));
        var reopened = new WebJobHistory(root);
        assertEquals("result", reopened.read("alice", id).getAsJsonArray("output").get(0).getAsString());
        assertEquals(1, reopened.list("alice").size());
        assertTrue(reopened.list("bob").isEmpty());
        assertThrows(java.io.IOException.class, () -> reopened.read("bob", id));
        assertThrows(IllegalArgumentException.class, () -> reopened.read("alice", "../secret"));
    }
    @Test void damagedSummariesDoNotBreakListing() throws Exception {
        String id = UUID.randomUUID().toString();
        var history = new WebJobHistory(root);
        history.save("alice", Map.of("id", id, "created", 123L, "command", "DUPE2", "state", "completed"));
        try (var files = Files.walk(root)) {
            Path meta = files.filter(p -> p.toString().endsWith(".meta")).findFirst().orElseThrow();
            Files.writeString(meta, "{broken");
        }
        assertTrue(history.list("alice").isEmpty());
    }
}
