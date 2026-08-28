/*
 * This file is part of DrFTPD, Distributed FTP Daemon.
 */
package org.drftpd.master.commands.serverstatus;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServerStatusTest {

    @Test
    void formatsLoadAverageWithoutUsingTheSystemLocale() {
        assertEquals("1.25", ServerStatus.formatLoadAverage(1.25D));
        assertEquals("unavailable", ServerStatus.formatLoadAverage(-1.0D));
    }

    @Test
    void parsesLinuxLoadAverageIntervals() {
        ServerStatus.LoadAverages loadAverages =
                ServerStatus.parseLoadAverages("1.25 2.50 3.75 2/100 1234\n");

        assertEquals(1.25D, loadAverages.oneMinute());
        assertEquals(2.50D, loadAverages.fiveMinutes());
        assertEquals(3.75D, loadAverages.fifteenMinutes());
        assertThrows(IllegalArgumentException.class, () -> ServerStatus.parseLoadAverages("1.25"));
    }
}
