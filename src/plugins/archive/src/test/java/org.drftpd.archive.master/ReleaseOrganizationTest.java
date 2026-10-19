package org.drftpd.archive.master;

import org.drftpd.archive.master.archivetypes.ReleaseOrganization;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReleaseOrganizationTest {
    @Test void extractsAllRequestedAnimeNames() {
        String show = "Bleach.Thousand-Year.Blood.War";
        for (String marker : new String[] {"Part.4.S01E1005", "Part.4.E05",
                "Part.4.E1005", "OAV01", "Part.2.E05", "VOL26", "S03.Vol02.FINAL", "E05"}) {
            String release = show + "." + marker + ".MULTi.1080p.WEB.H264-AMB3R";
            assertEquals(show, ReleaseOrganization.showName(release), release);
            assertTrue(ReleaseOrganization.releaseName(release));
        }
    }

    @Test void preservesTitleNumbersAndRejectsContainers() {
        assertEquals("1670", ReleaseOrganization.showName("1670.S01E01.MULTI.WEB-GRP"));
        assertEquals("The.100", ReleaseOrganization.showName("The.100.E1005.FRENCH.WEB-GRP"));
        assertNull(ReleaseOrganization.showName("Movie.2025.FRENCH.WEB-GRP"));
        assertFalse(ReleaseOrganization.releaseName("TV-FR"));
        assertFalse(ReleaseOrganization.releaseName("My.Show"));
        assertFalse(ReleaseOrganization.releaseName("Season.01"));
    }
}
