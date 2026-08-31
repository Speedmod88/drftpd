/*
 * This file is part of DrFTPD, Distributed FTP Daemon.
 */
package org.drftpd.master.sitebot.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SiteBotConfigTest {

    @Test
    void usesAutomaticWorkerCountForAutoAndInvalidValues() {
        assertEquals(6, SiteBotConfig.parseThreadCount("auto", 6));
        assertEquals(6, SiteBotConfig.parseThreadCount("0", 6));
        assertEquals(6, SiteBotConfig.parseThreadCount("invalid", 6));
    }

    @Test
    void acceptsPositiveWorkerAndQueueCounts() {
        assertEquals(9, SiteBotConfig.parseThreadCount("9", 6));
        assertEquals(32, SiteBotConfig.parsePositiveInteger("32", 20, "test"));
        assertEquals(20, SiteBotConfig.parsePositiveInteger("-1", 20, "test"));
    }

    @Test
    void acceptsNonNegativeOperDelay() {
        assertEquals(0L, SiteBotConfig.parseNonNegativeLong("0", 25L, "test"));
        assertEquals(1000L, SiteBotConfig.parseNonNegativeLong("1000", 25L, "test"));
        assertEquals(25L, SiteBotConfig.parseNonNegativeLong("-1", 25L, "test"));
        assertEquals(25L, SiteBotConfig.parseNonNegativeLong("invalid", 25L, "test"));
    }

    @Test
    void detectsRawOperCommands() {
        assertTrue(SiteBotConfig.isOperCommand("OPER drftpd password"));
        assertTrue(SiteBotConfig.isOperCommand("  oper drftpd password"));
        assertFalse(SiteBotConfig.isOperCommand("OPERATE drftpd password"));
        assertFalse(SiteBotConfig.isOperCommand("MODE drftpd +o"));
        assertFalse(SiteBotConfig.isOperCommand(null));
    }
}
