/*
 * This file is part of DrFTPD, Distributed FTP Daemon.
 *
 * DrFTPD is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */
package org.drftpd.slave;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class SlaveTransferTlsConfigurationTest {

    private static final String[] SUPPORTED_PROTOCOLS = {"TLSv1.2", "TLSv1.3"};
    private static final String[] SUPPORTED_CIPHERS = {
            "TLS_AES_256_GCM_SHA384",
            "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
            "TLS_RSA_WITH_AES_128_CBC_SHA"
    };

    @Test
    public void testConfiguredProtocolRestrictsDataTransfers() {
        Properties config = new Properties();
        config.setProperty("protocol.1", "TLSv1.2");

        assertArrayEquals(new String[]{"TLSv1.2"},
                Slave.selectSSLProtocols(config, SUPPORTED_PROTOCOLS));
    }

    @Test
    public void testProtocolNumberingMayContainGaps() {
        Properties config = new Properties();
        config.setProperty("protocol.2", "TLSv1.2");

        assertArrayEquals(new String[]{"TLSv1.2"},
                Slave.selectSSLProtocols(config, SUPPORTED_PROTOCOLS));
    }

    @Test
    public void testMissingProtocolConfigurationUsesJvmDefaults() {
        assertNull(Slave.selectSSLProtocols(new Properties(), SUPPORTED_PROTOCOLS));
    }

    @Test
    public void testUnsupportedConfiguredProtocolFailsInsteadOfUsingJvmDefaults() {
        Properties config = new Properties();
        config.setProperty("protocol.1", "TLSv9");

        assertThrows(IllegalArgumentException.class,
                () -> Slave.selectSSLProtocols(config, SUPPORTED_PROTOCOLS));
    }

    @Test
    public void testCipherWhitelistAndBlacklistAreApplied() {
        Properties config = new Properties();
        config.setProperty("cipher.whitelist.1", ".*_GCM_.*");
        config.setProperty("cipher.blacklist.1", "TLS_AES_.*");

        assertArrayEquals(new String[]{"TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384"},
                Slave.selectCipherSuites(config, SUPPORTED_CIPHERS));
    }

    @Test
    public void testUnmatchedCipherWhitelistFailsInsteadOfUsingJvmDefaults() {
        Properties config = new Properties();
        config.setProperty("cipher.whitelist.1", ".*_NOT_SUPPORTED");

        assertThrows(IllegalArgumentException.class,
                () -> Slave.selectCipherSuites(config, SUPPORTED_CIPHERS));
    }
}
