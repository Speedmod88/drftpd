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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    public void concurrentFreshChecksumLookupsShareOneSlaveScan() throws Exception {
        BlockingChecksumRemoteSlave slave = new BlockingChecksumRemoteSlave("healthy", 9876L);
        TestFileHandle file = new TestFileHandle(slave);
        int callers = 8;
        ExecutorService executor = Executors.newFixedThreadPool(callers);
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> results = new ArrayList<>();

        try {
            for (int i = 0; i < callers; i++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5L, TimeUnit.SECONDS));
                    return file.getCheckSumFromSlave();
                }));
            }

            assertTrue(ready.await(5L, TimeUnit.SECONDS));
            start.countDown();
            assertTrue(slave.awaitScanStarted());
            Thread.sleep(100L);
            slave.releaseScan();

            for (Future<Long> result : results) {
                assertEquals(9876L, result.get(5L, TimeUnit.SECONDS));
            }
            assertEquals(1, slave.getScanCount());

            assertEquals(9876L, file.getCheckSumFromSlave());
            assertEquals(2, slave.getScanCount());
        } finally {
            slave.releaseScan();
            executor.shutdownNow();
        }
    }

    @Test
    public void failedConcurrentChecksumCanBeRetried() throws Exception {
        BlockingChecksumRemoteSlave slave = new BlockingChecksumRemoteSlave(
                "failed", new IOException("disk read failed"));
        TestFileHandle file = new TestFileHandle(slave);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            Future<Long> first = executor.submit(() -> {
                assertTrue(start.await(5L, TimeUnit.SECONDS));
                return file.getCheckSumFromSlave();
            });
            Future<Long> second = executor.submit(() -> {
                assertTrue(start.await(5L, TimeUnit.SECONDS));
                return file.getCheckSumFromSlave();
            });

            start.countDown();
            assertTrue(slave.awaitScanStarted());
            Thread.sleep(100L);
            slave.releaseScan();

            assertThrows(Exception.class, () -> first.get(5L, TimeUnit.SECONDS));
            assertThrows(Exception.class, () -> second.get(5L, TimeUnit.SECONDS));
            assertEquals(1, slave.getScanCount());

            assertThrows(NoAvailableSlaveException.class, file::getCheckSumFromSlave);
            assertEquals(2, slave.getScanCount());
        } finally {
            slave.releaseScan();
            executor.shutdownNow();
        }
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
        protected final Long _checksum;
        protected final Exception _failure;

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

    private static class BlockingChecksumRemoteSlave extends ChecksumRemoteSlave {
        private final AtomicInteger _scanCount = new AtomicInteger();
        private final CountDownLatch _scanStarted = new CountDownLatch(1);
        private final CountDownLatch _releaseScan = new CountDownLatch(1);

        private BlockingChecksumRemoteSlave(String name, long checksum) {
            super(name, checksum);
        }

        private BlockingChecksumRemoteSlave(String name, Exception failure) {
            super(name, failure);
        }

        @Override
        public long getCheckSumForPath(String path) throws IOException, SlaveUnavailableException {
            _scanCount.incrementAndGet();
            _scanStarted.countDown();
            try {
                if (!_releaseScan.await(5L, TimeUnit.SECONDS)) {
                    throw new IOException("Timed out waiting to release test checksum scan");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting to release test checksum scan", e);
            }
            return super.getCheckSumForPath(path);
        }

        private boolean awaitScanStarted() throws InterruptedException {
            return _scanStarted.await(5L, TimeUnit.SECONDS);
        }

        private void releaseScan() {
            _releaseScan.countDown();
        }

        private int getScanCount() {
            return _scanCount.get();
        }
    }
}
