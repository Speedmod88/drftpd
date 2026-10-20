package org.drftpd.webadmin.master;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WebAdminArchiveTest {
    @Test void exposesSavedRulesInNumericOrder() {
        Properties p = new Properties();
        for (int id : new int[] {10, 2, 1}) {
            p.setProperty(id + ".type", "MoveReleaseToSpecificFolder");
            p.setProperty(id + ".recursive", "true");
        }
        p.setProperty("1.recursive", "false");
        p.setProperty("10.preview", "false");
        var rules = WebAdminArchive.rules(p);
        assertEquals(3, rules.size());
        assertEquals(1, rules.get(0).id());
        assertTrue(rules.get(0).previewOnly());
        assertFalse(rules.get(2).previewOnly());
    }

    @Test void previewOverridesNeverMutateSavedRules() {
        Properties original = new Properties();
        original.setProperty("2.type", "MoveReleaseOffSlavesToMostFreeSlaves");
        original.setProperty("2.section", "OLD");
        original.setProperty("2.preview", "false");
        var result = WebAdminArchive.previewProperties(original, 2, "TV", "/ARCHIVES/TV", "rls:${rls}");
        assertEquals("OLD", original.getProperty("2.section"));
        assertEquals("false", original.getProperty("2.preview"));
        assertEquals("TV", result.getProperty("2.section"));
        assertEquals("true", result.getProperty("2.preview"));
        assertEquals("true", result.getProperty("2.recursive"));
        assertEquals("MoveReleaseToSpecificFolder", result.getProperty("2.type"));
        assertEquals("rls:${rls}", result.getProperty("2.todirectorytype"));
        assertThrows(IllegalArgumentException.class, () -> WebAdminArchive.previewProperties(original, 3, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> WebAdminArchive.previewProperties(original, 2, null, "", null));
    }
}
