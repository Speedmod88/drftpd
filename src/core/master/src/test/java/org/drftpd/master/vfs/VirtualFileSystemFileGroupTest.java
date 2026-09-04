/*
 * This file is part of DrFTPD, Distributed FTP Daemon.
 *
 * DrFTPD is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or any later version.
 */
package org.drftpd.master.vfs;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class VirtualFileSystemFileGroupTest {

    @Test
    public void testLegacyModeKeepsRaceGroup() {
        assertEquals("RACERS", VirtualFileSystemFile.resolveGroup(
                "RACERS", Set.of("EvoI"), false, ignored -> true));
    }

    @Test
    public void testCapableSlavesBecomeSortedFileGroup() {
        assertEquals("EvoI+EvoIII", VirtualFileSystemFile.resolveGroup(
                "RACERS", Set.of("EvoIII", "EvoI"), true, ignored -> true));
    }

    @Test
    public void testAnyLegacySlaveKeepsRaceGroup() {
        assertEquals("RACERS", VirtualFileSystemFile.resolveGroup(
                "RACERS", Set.of("EvoI", "Legacy"), true,
                slave -> !slave.equals("Legacy")));
    }
}
