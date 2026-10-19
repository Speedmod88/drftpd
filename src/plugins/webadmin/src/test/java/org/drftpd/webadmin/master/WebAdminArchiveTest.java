package org.drftpd.webadmin.master;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WebAdminArchiveTest {
    @Test void exposesOnlyRecursiveFolderRulesInNumericOrder() {
        Properties p = new Properties();
        for (int id : new int[] {10, 2, 1}) {
            p.setProperty(id + ".type", "MoveReleaseToSpecificFolder");
            p.setProperty(id + ".recursive", "true");
        }
        p.setProperty("1.recursive", "false");
        p.setProperty("10.preview", "false");
        var rules = WebAdminArchive.rules(p);
        assertEquals(2, rules.size());
        assertEquals(2, rules.get(0).id());
        assertTrue(rules.get(0).previewOnly());
        assertFalse(rules.get(1).previewOnly());
    }
}
