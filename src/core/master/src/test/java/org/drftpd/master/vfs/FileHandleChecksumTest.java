/*
 * This file is part of DrFTPD, Distributed FTP Daemon.
 *
 * DrFTPD is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */
package org.drftpd.master.vfs;

import org.drftpd.master.exceptions.NoAvailableSlaveException;
import org.drftpd.master.exceptions.SlaveUnavailableException;
import org.drftpd.master.slavemanagement.RemoteSlave;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class FileHandleChecksumTest {

    @Test
    public void checksumLookupTriesEachAvailableReplicaOnce() throws Exception {
        TestFileHandle file = new TestFileHandle(
                new ChecksumRemoteSlave("failed", new IOException("disk read failed")),
                new ChecksumRemoteSlave("healthy", 1234L));

        assertEquals(1234L, file.getCheckSumFromSlave());
        assertEquals(1234L, file.getStoredChecksum());
    }

    @Test
    public void freshChecksumLookupIgnoresCachedValue() throws Exception {
        TestFileHandle file = new TestFileHandle(new ChecksumRemoteSlave("healthy", 5678L));
        file.setCachedChecksum(1234L);

        assertEquals(5678L, file.getCheckSumFromSlave());
        assertEquals(5678L, file.getStoredChecksum());
    }

    @Test
    public void checksumLookupFailsCleanlyAfterAllReplicasFail() {
        TestFileHandle file = new TestFileHandle(
                new ChecksumRemoteSlave("failed-1", new IOException("disk read failed")),
                new ChecksumRemoteSlave("failed-2", new SlaveUnavailableException("connection replaced")));

        assertThrows(NoAvailableSlaveException.class, file::getCheckSumFromSlave);
    }

    private static class TestFileHandle extends FileHandle {
        private final Collection<RemoteSlave> _slaves;
        private final VirtualFileSystemFile _inode =
                new VirtualFileSystemFile("test", "test", 1L, "test");
        private long _storedChecksum;

        private TestFileHandle(RemoteSlave... slaves) {
            super("/checksum-test.bin");
            _slaves = Arrays.asList(slaves);
        }

        @Override
        protected VirtualFileSystemFile getInode() {
            return _inode;
        }

        @Override
        public long getSize() {
            return 1L;
        }

        @Override
        public Collection<RemoteSlave> getAvailableSlaves() {
            return _slaves;
        }

        @Override
        public void setCheckSum(long checksum) {
            _storedChecksum = checksum;
        }

        private long getStoredChecksum() {
            return _storedChecksum;
        }

        private void setCachedChecksum(long checksum) {
            _inode.getKeyedMap().setObject(VirtualFileSystemFile.CRC, checksum);
        }
    }

    private static class ChecksumRemoteSlave extends RemoteSlave {
        private final Long _checksum;
        private final Exception _failure;

        private ChecksumRemoteSlave(String name, long checksum) {
            super(name);
            _checksum = checksum;
            _failure = null;
        }

        private ChecksumRemoteSlave(String name, Exception failure) {
            super(name);
            _checksum = null;
            _failure = failure;
        }

        @Override
        public long getCheckSumForPath(String path) throws IOException, SlaveUnavailableException {
            if (_failure instanceof IOException) {
                throw (IOException) _failure;
            }
            if (_failure instanceof SlaveUnavailableException) {
                throw (SlaveUnavailableException) _failure;
            }
            return _checksum;
        }
    }
}
