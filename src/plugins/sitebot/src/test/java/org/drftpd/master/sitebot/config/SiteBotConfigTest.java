/*
 * This file is part of DrFTPD, Distributed FTP Daemon.
 */
package org.drftpd.master.sitebot.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
