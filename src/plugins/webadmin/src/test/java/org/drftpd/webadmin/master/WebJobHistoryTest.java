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
