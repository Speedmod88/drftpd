package org.drftpd.master.commands.slavemanagement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SlaveStatusLineTest {
    @Test
    void fullWarningIsAppendedToStatusLine() {
        String status = "-= EvoIII =- ONLINE FULL UP: 0 @ 0B/s DN: 0 @ 0B/s DF: 0B/168.0TB";
        String warning = "EvoIII FULL (minfreespace): root.1 free=999.9GB min=1.5TB. Upload disk-selection rules still apply.";
        String result = SlaveManagement.appendDiskSpaceWarning(status, warning);
        assertEquals(status + " | " + warning, result);
        assertFalse(result.contains("\n"));
    }

    @Test
    void ordinaryAndOfflineStatusRemainUnchanged() {
        for (String status : new String[] {"-= EvoI =- ONLINE", "-= EvoI =- OFFLINE"}) {
            assertEquals(status, SlaveManagement.appendDiskSpaceWarning(status, null));
            assertEquals(status, SlaveManagement.appendDiskSpaceWarning(status, ""));
        }
    }

    @Test
    void remergingStatusCanIncludeCapacityWarning() {
        assertEquals("REMERGING | FULL (minfreespace)",
                SlaveManagement.appendDiskSpaceWarning("REMERGING", "FULL (minfreespace)"));
    }
}
