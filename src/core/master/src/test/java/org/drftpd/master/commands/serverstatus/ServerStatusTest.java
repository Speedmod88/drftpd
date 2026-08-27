/*
 * This file is part of DrFTPD, Distributed FTP Daemon.
 */
package org.drftpd.master.commands.serverstatus;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServerStatusTest {

    @Test
    void formatsLoadAverageWithoutUsingTheSystemLocale() {
        assertEquals("1.25", ServerStatus.formatLoadAverage(1.25D));
        assertEquals("unavailable", ServerStatus.formatLoadAverage(-1.0D));
    }
}
