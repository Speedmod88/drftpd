package org.drftpd.webadmin.master;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryCredentialsTest {
    @Test void loginSecretsAreUserScopedExpireAndClearOnLogout() throws Exception {
        AtomicLong time = new AtomicLong(100);
        try (var credentials = new RecoveryCredentials(time::get)) {
            credentials.remember("one", "evo", "password-one", 200);
            assertThrows(IOException.class, () -> credentials.forUser("other"));
            var copy = credentials.forUser("evo");
            assertArrayEquals("password-one".toCharArray(), copy.password);
            copy.close();
            assertArrayEquals(new char[12], copy.password);
            credentials.remember("two", "evo", "password-two", 300);
            try (var current = credentials.forUser("evo")) {
                assertArrayEquals("password-two".toCharArray(), current.password);
            }
            credentials.forget("two");
            time.set(200);
            assertThrows(IOException.class, () -> credentials.forUser("evo"));
            credentials.remember("three", "evo", "another", 400);
            credentials.close();
            assertThrows(IOException.class, () -> credentials.forUser("evo"));
        }
    }
}
