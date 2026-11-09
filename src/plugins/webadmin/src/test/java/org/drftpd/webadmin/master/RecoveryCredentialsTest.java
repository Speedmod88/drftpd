package org.drftpd.webadmin.master;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryCredentialsTest {
    @Test void loginSecretsAreUserScopedExpireAndClearOnLogout() throws Exception {
        AtomicLong time = new AtomicLong(100);
        try (var credentials = new RecoveryCredentials(time::get)) {
            credentials.remember("one", "Slave0", "password-one", 200);
            assertThrows(IOException.class, () -> credentials.forUser("other"));
            var copy = credentials.forUser("Slave0");
            assertArrayEquals("password-one".toCharArray(), copy.password);
            copy.close();
            assertArrayEquals(new char[12], copy.password);
            credentials.remember("two", "Slave0", "password-two", 300);
            try (var current = credentials.forUser("Slave0")) {
                assertArrayEquals("password-two".toCharArray(), current.password);
            }
            credentials.forget("two");
            time.set(200);
            assertThrows(IOException.class, () -> credentials.forUser("Slave0"));
            credentials.remember("three", "Slave0", "another", 400);
            credentials.close();
            assertThrows(IOException.class, () -> credentials.forUser("Slave0"));
        }
    }
}
