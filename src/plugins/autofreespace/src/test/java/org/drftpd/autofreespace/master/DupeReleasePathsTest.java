package org.drftpd.autofreespace.master;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DupeReleasePathsTest {
    private final List<String> roots = List.of("/ARCHIVES/TV-HD-FRENCH/1080P");
    private final String episode = "19-2.S01E07.FRENCH.1080p.WEB.x264-HiggsBoson";

    @Test void rejectsContainers() {
        for (String name : List.of("0", "19-2.WEB", "Saison.01", "Sample", "DISK1", "2026-1025")) {
            assertFalse(DupeReleasePaths.inSection(roots.get(0) + "/" + name, roots), name);
        }
    }
    @Test void acceptsNestedEpisodesAndDirectReleases() {
        assertTrue(DupeReleasePaths.inSection(roots.get(0) + "/19-2.WEB/Saison.01/" + episode, roots));
        assertTrue(DupeReleasePaths.inSection(roots.get(0) + "/0/" + episode, roots));
        assertTrue(DupeReleasePaths.inSection(roots.get(0) + "/" + episode, roots));
    }
    @Test void rejectsChildrenAndOutsidePaths() {
        assertFalse(DupeReleasePaths.inSection(roots.get(0) + "/" + episode + "/Sample", roots));
        assertFalse(DupeReleasePaths.inSection(roots.get(0) + "/" + episode + "/Sample-GRP", roots));
        assertFalse(DupeReleasePaths.inSection(roots.get(0) + "-other/" + episode, roots));
        assertFalse(DupeReleasePaths.inSection(roots.get(0), roots));
    }
    @Test void keepsAppsMusicAndMultidiscReleaseRoots() {
        for (String name : List.of("AOMEI.Partition.Assistant.v10.8.Bootable.iSO-rG",
                "AUTODESK.AUTOCAD.ARCHITECTURE.V2021.FRENCH-ENGiNE",
                "VA-Skyrager_Presents_Coconut_Dreams_Vol_1-16BIT-WEB-FLAC-2026-OBZEN",
                "Pacmania_AGA_v1.1_Installed_WHDLoad_AMIGA-bADkARMA")) {
            assertTrue(DupeReleasePaths.inSection("/APPS/" + name, List.of("/APPS")), name);
            assertFalse(DupeReleasePaths.inSection("/APPS/" + name + "/DISK1", List.of("/APPS")));
        }
    }
    @Test void respectsDatedSectionRoots() {
        assertTrue(DupeReleasePaths.inSection("/0DAYS/2026-1025/App.v1-GRP", List.of("/0DAYS/2026-1025")));
        assertFalse(DupeReleasePaths.inSection("/0DAYS/2026-1024/App.v1-GRP", List.of("/0DAYS/2026-1025")));
    }
}
