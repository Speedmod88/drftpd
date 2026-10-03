package org.drftpd.zipscript.master.audit;

import org.junit.jupiter.api.Test;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;

class IntegrityConfigTest {
    @Test void conservativeDefaultsPreserveTransferBehavior() {
        IntegrityConfig config = IntegrityConfig.read(new Properties());
        assertFalse(config.enabled());
        assertEquals(300000, config.idleMillis());
        assertEquals(90L * 86400000, config.intervalMillis());
        assertFalse(config.includes("/PRE/team/file.rar"));
        assertTrue(config.includes("/MOVIES/release/file.rar"));
    }
    @Test void scopedPathsCannotMatchAdjacentSectionNames() {
        Properties p = new Properties();
        p.setProperty("audit.paths", "/ARCHIVES,/TV");
        p.setProperty("audit.slaves", "Evo EvoI");
        IntegrityConfig config = IntegrityConfig.read(p);
        assertTrue(config.includes("/TV/release/file"));
        assertFalse(config.includes("/TV-HD/release/file"));
        assertEquals(2, config.slaves().size());
    }
    @Test void invalidLimitsAndPathsFailCleanly() {
        for (String value : new String[]{"0", "-1", "1000000", "bad"}) {
            Properties p = new Properties();
            p.setProperty("audit.steps.per.tick", value);
            assertThrows(IllegalArgumentException.class, () -> IntegrityConfig.read(p));
        }
        Properties p = new Properties();
        p.setProperty("audit.paths", "../site");
        assertThrows(IllegalArgumentException.class, () -> IntegrityConfig.read(p));
    }
}
