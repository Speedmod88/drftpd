package org.drftpd.webadmin.master;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class DupeSelectionTest {
    @Test void permitsExplicitReviewedSelectionsButRejectsInjectedPaths() {
        var offered = List.of("/A/Movie.KEEP-GRP", "/B/Movie.UNKNOWN-GRP");
        assertEquals(offered, WebAdminDupe.selection(offered, offered));
        assertEquals(List.of(offered.get(0)), WebAdminDupe.selection(offered, List.of(offered.get(0), offered.get(0))));
        assertThrows(IllegalArgumentException.class, () -> WebAdminDupe.selection(offered, List.of("/other")));
        assertThrows(IllegalArgumentException.class, () -> WebAdminDupe.selection(List.of("/"), List.of("/")));
        assertThrows(IllegalArgumentException.class, () -> WebAdminDupe.selection(offered, List.of()));
    }
}
