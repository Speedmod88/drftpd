package org.drftpd.traffic.master;

import org.drftpd.master.network.BaseFtpConnection;
import org.drftpd.master.usermanager.User;
import org.drftpd.master.vfs.FileHandle;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TrafficTypeTest {

    @Test
    void minimumSpeedGraceDefaultsToZero() {
        TestTrafficType type = new TestTrafficType(properties());

        assertEquals(0L, type.minimumSpeedGrace());
    }

    @Test
    void minimumSpeedGraceIsConvertedFromSecondsToMilliseconds() {
        Properties properties = properties();
        properties.setProperty("1.minspeed.grace", "30");

        TestTrafficType type = new TestTrafficType(properties);

        assertEquals(30_000L, type.minimumSpeedGrace());
    }

    @Test
    void negativeMinimumSpeedGraceIsRejected() {
        Properties properties = properties();
        properties.setProperty("1.minspeed.grace", "-1");

        assertThrows(RuntimeException.class, () -> new TestTrafficType(properties));
    }

    private static Properties properties() {
        Properties properties = new Properties();
        properties.setProperty("1.name", "test");
        return properties;
    }

    private static final class TestTrafficType extends TrafficType {
        private TestTrafficType(Properties properties) {
            super(properties, 1, "test");
        }

        private long minimumSpeedGrace() {
            return getMinSpeedGrace();
        }

        @Override
        public void doAction(User user, FileHandle file, boolean isStor, long minspeed, long speed,
                             long transfered, BaseFtpConnection conn, String slaveName) {
        }
    }
}
