package org.drftpd.autofreespace.master;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DupeReviewStatusTest {
    @Test void protectsIncompleteAndUnknown() {
        assertEquals("KEEP", Dupe2Utils.reviewStatus("completed", true));
        assertEquals("Can delete", Dupe2Utils.reviewStatus("completed", false));
        assertEquals("NOT COMPLETED", Dupe2Utils.reviewStatus("incomplete", false));
        assertEquals("UNKNOWN", Dupe2Utils.reviewStatus("unknown", false));
        assertEquals("NOT COMPLETED", Dupe2Utils.reviewStatus("incomplete", true));
    }
}
