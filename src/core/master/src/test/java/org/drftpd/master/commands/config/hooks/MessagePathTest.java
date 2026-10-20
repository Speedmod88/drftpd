package org.drftpd.master.commands.config.hooks;

import org.drftpd.master.permissions.MessagePathPermission;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.StringTokenizer;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MessagePathTest {
    @TempDir Path temp;

    @Test
    void missingMessageIsIgnoredBeforeAccessingGlobalPermissions() {
        // No GlobalContext is initialized: an invalid rule must return before registration.
        DefaultConfigHandler handler = new DefaultConfigHandler();
        String missing = "missing-" + UUID.randomUUID() + "/incoming.txt";
        assertDoesNotThrow(() -> handler.handleMsgPath("msgpath",
                new StringTokenizer("/incoming/ " + missing + " *")));
    }

    @Test
    void readableMessagePreservesLinesAndPermissions() throws Exception {
        Path message = temp.resolve("incoming.txt");
        Files.write(message, List.of("Welcome", "", "Upload rules"));
        MessagePathPermission permission = new MessagePathPermission("/incoming/",
                message.toString(), List.of("*"));
        assertEquals(List.of("Welcome", "", "Upload rules"), permission.getMessage());
        assertFalse(permission.check(null));
    }
}
