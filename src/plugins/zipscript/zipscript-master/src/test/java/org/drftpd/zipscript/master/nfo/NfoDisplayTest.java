package org.drftpd.zipscript.master.nfo;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;

class NfoDisplayTest {
    @Test void sanitizesTerminalControlsAndSplitsReplyLines() {
        assertEquals(List.of("Red", "250 forged", "last"),
                NfoDisplay.safeLines("\u001b[31mRed\u001b[0m\r\n250 forged\rlast\u0000\u0007", 10));
    }
    @Test void capsLineCountAndWidth() {
        assertEquals(List.of("one", "[NFO display truncated]"), NfoDisplay.safeLines("one\ntwo", 1));
        assertEquals(512, NfoDisplay.safeLines("x".repeat(1000), 2).get(0).length());
        assertTrue(NfoDisplay.safeLines("x".repeat(65537), 200).isEmpty());
    }
    @Test void validatesLimitsAndSupportsDisable() {
        Properties config = new Properties();
        assertTrue(NfoDisplay.Settings.read(config).enabled());
        config.setProperty("enabled", "false");
        assertFalse(NfoDisplay.Settings.read(config).enabled());
        config.setProperty("cache.entries", "1000000");
        assertThrows(IllegalArgumentException.class, () -> NfoDisplay.Settings.read(config));
    }
}
