package org.drftpd.webadmin.master;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DupeLimitTest {
    @Test void validatesLimit() {
        assertEquals(10, WebAdminDupe.limit(null));
        assertEquals(1, WebAdminDupe.limit(1));
        assertEquals(1000, WebAdminDupe.limit(1000));
        assertThrows(IllegalArgumentException.class, () -> WebAdminDupe.limit(0));
        assertThrows(IllegalArgumentException.class, () -> WebAdminDupe.limit(-1));
        assertThrows(IllegalArgumentException.class, () -> WebAdminDupe.limit(1001));
    }
}
