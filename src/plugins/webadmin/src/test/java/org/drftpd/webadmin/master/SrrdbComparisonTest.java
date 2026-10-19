package org.drftpd.webadmin.master;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SrrdbComparisonTest {
    @Test void comparesOnlyKnownCrcAndSize() {
        var remote = new SrrdbClient.RemoteFile("release.sfv", 42, "ABCDEF12");
        assertEquals("MISSING", SrrdbRecovery.comparisonStatus(new SrrdbRecovery.LocalFile(false, "", -1, ""), remote));
        assertEquals("PRESENT - CRC UNKNOWN", SrrdbRecovery.comparisonStatus(new SrrdbRecovery.LocalFile(true, "release.sfv", 42, ""), remote));
        assertEquals("MATCH", SrrdbRecovery.comparisonStatus(new SrrdbRecovery.LocalFile(true, "release.sfv", 42, "abcdef12"), remote));
        assertEquals("MISMATCH", SrrdbRecovery.comparisonStatus(new SrrdbRecovery.LocalFile(true, "release.sfv", 43, "ABCDEF12"), remote));
        assertEquals("MISMATCH", SrrdbRecovery.comparisonStatus(new SrrdbRecovery.LocalFile(true, "release.sfv", 42, "12345678"), remote));
    }
}
