package org.drftpd.webadmin.master;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DupeBulkMoveTest {
    @Test void bulkVerificationDoesNotRelaxDestructiveSelectionLimits() {
        List<String> paths = IntStream.range(0, 101).mapToObj(i -> "/TV/Show-" + i).toList();
        assertEquals(paths, WebAdminDupe.selection(paths, paths, 10000));
        assertThrows(IllegalArgumentException.class, () -> WebAdminDupe.selection(paths, paths));
        assertThrows(IllegalArgumentException.class, () -> WebAdminDupe.selection(paths, List.of("/OTHER"), 10000));
    }

    @Test void rejectsMovingIntoSelfOrDescendantButNotSimilarNames() {
        assertThrows(IllegalArgumentException.class, () -> WebAdminDupe.validateMovePaths("/TV/Show", "/TV/Show"));
        assertThrows(IllegalArgumentException.class, () -> WebAdminDupe.validateMovePaths("/TV/Show", "/TV/Show/Subs"));
        assertDoesNotThrow(() -> WebAdminDupe.validateMovePaths("/TV/Show", "/TV/Show2"));
    }
}
