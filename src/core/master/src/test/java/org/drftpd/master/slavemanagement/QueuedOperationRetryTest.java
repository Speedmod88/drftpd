package org.drftpd.master.slavemanagement;

import org.drftpd.common.exceptions.RemoteIOException;
import org.drftpd.common.io.PermissionDeniedException;
import org.drftpd.master.exceptions.SlaveUnavailableException;
import org.drftpd.slave.protocol.QueuedOperation;
import org.junit.jupiter.api.Test;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QueuedOperationRetryTest {
    @Test
    void permissionFailureRetriesHeadBeforeDependentOperation() throws Exception {
        RetrySlave slave = new RetrySlave();
        slave.failures = 2;
        slave.processQueue();
        assertEquals(List.of("/source", "/source", "/source", "/destination"), slave.attempts);
        assertEquals(List.of(60000L, 120000L), slave.delays);
        assertTrue(slave.getRenameQueue().isEmpty());
        assertEquals(3, slave.flushes);
        assertEquals(0, slave.disconnects);
    }

    @Test
    void persistentFailureBackoffIsCappedAndQueueIsNotDiscarded() {
        RetrySlave slave = new RetrySlave();
        slave.failures = 100;
        slave.stopAfterWaits = 5;
        assertThrows(SlaveUnavailableException.class, slave::processQueue);
        assertEquals(List.of(60000L, 120000L, 240000L, 300000L, 300000L), slave.delays);
        assertEquals(2, slave.getRenameQueue().size());
        assertEquals("/source", slave.getRenameQueue().peek().getSource());
        assertEquals(0, slave.disconnects);
    }

    @Test
    void cancelledHeadIsNotReissuedAfterRetryWait() throws Exception {
        RetrySlave slave = new RetrySlave();
        slave.failures = 1;
        slave.cancelHeadDuringWait = true;
        slave.processQueue();
        assertEquals(List.of("/source", "/destination"), slave.attempts);
        assertTrue(slave.getRenameQueue().isEmpty());
    }

    @Test
    void missingFileStillAcknowledgesOperationWithoutRetry() throws Exception {
        RetrySlave slave = new RetrySlave();
        slave.failures = 1;
        slave.failure = new FileNotFoundException("already removed");
        slave.processQueue();
        assertTrue(slave.delays.isEmpty());
        assertTrue(slave.getRenameQueue().isEmpty());
    }

    @Test
    void transportFailureDoesNotBecomeFilesystemRetry() {
        RetrySlave slave = new RetrySlave();
        slave.transportFailure = true;
        assertThrows(SlaveUnavailableException.class, slave::processQueue);
        assertTrue(slave.delays.isEmpty());
        assertEquals(2, slave.getRenameQueue().size());
    }

    @Test
    void retryWaitStopsOnOfflineConnection() {
        RetrySlave slave = new RetrySlave();
        slave.online = false;
        assertThrows(SlaveUnavailableException.class,
                () -> slave.realWait(slave.getConnectionGeneration(), 60000L));
    }

    @Test
    void retryWaitRejectsStaleConnection() {
        RetrySlave slave = new RetrySlave();
        assertThrows(SlaveUnavailableException.class,
                () -> slave.realWait(slave.getConnectionGeneration() - 1, 60000L));
    }

    @Test
    void retryWaitPreservesInterruption() {
        RetrySlave slave = new RetrySlave();
        Thread.currentThread().interrupt();
        try {
            assertThrows(SlaveUnavailableException.class,
                    () -> slave.realWait(slave.getConnectionGeneration(), 60000L));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    private static class RetrySlave extends RemoteSlave {
        final List<String> attempts = new ArrayList<>();
        final List<Long> delays = new ArrayList<>();
        IOException failure = new PermissionDeniedException("cannot create destination folder");
        int failures;
        int flushes;
        int disconnects;
        int stopAfterWaits = Integer.MAX_VALUE;
        boolean online = true;
        boolean transportFailure;
        boolean cancelHeadDuringWait;

        RetrySlave() {
            super("queue-retry-test");
            getRenameQueue().add(new QueuedOperation("/source", "/destination"));
            getRenameQueue().add(new QueuedOperation("/destination", null));
        }

        @Override
        protected void sendQueuedOperation(QueuedOperation item) throws RemoteIOException, SlaveUnavailableException {
            attempts.add(item.getSource());
            if (transportFailure) throw new SlaveUnavailableException("connection lost");
            if (failures-- > 0) throw new RemoteIOException(failure);
        }

        @Override
        protected void reportQueuedOperationRetry(QueuedOperation item, IOException failure, long delay) {
            assertSame(item, getRenameQueue().peek());
        }

        @Override
        protected void waitForQueuedOperationRetry(long generation, long delay) throws SlaveUnavailableException {
            delays.add(delay);
            if (cancelHeadDuringWait) getRenameQueue().removeFirst();
            if (delays.size() >= stopAfterWaits) throw new SlaveUnavailableException("test connection ended");
        }

        void realWait(long generation, long delay) throws SlaveUnavailableException {
            super.waitForQueuedOperationRetry(generation, delay);
        }

        @Override public boolean isOnline() { return online; }
        @Override public void setOffline(String reason) { disconnects++; }
        @Override protected void scheduleQueuedOperationsCommit() { }
        @Override protected void flushQueuedOperationsCommit() { flushes++; }
    }
}
