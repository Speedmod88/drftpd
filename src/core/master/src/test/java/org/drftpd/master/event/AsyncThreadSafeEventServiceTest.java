/*
 * This file is part of DrFTPD, Distributed FTP Daemon.
 *
 * DrFTPD is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */
package org.drftpd.master.event;

import org.drftpd.master.vfs.VirtualFileSystemRoot;
import org.drftpd.master.vfs.event.VirtualFileSystemInodeCreatedEvent;
import org.drftpd.master.vfs.event.VirtualFileSystemLastModifiedEvent;
import org.drftpd.master.vfs.event.VirtualFileSystemSizeEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

public class AsyncThreadSafeEventServiceTest {

    private final VirtualFileSystemRoot inode = new VirtualFileSystemRoot();

    @Test
    public void testRemergeMetadataUpdatesForOnePathAreCoalesced() {
        AsyncThreadSafeEventService service = new AsyncThreadSafeEventService(false);

        withThreadName("RemergeThread - Evo", () -> {
            service.publishAsync(new VirtualFileSystemSizeEvent(inode, "/section/release", 100L));
            service.publishAsync(new VirtualFileSystemLastModifiedEvent(
                    inode, "/section/release", 200L));
        });

        assertEquals(1, service.getQueueSize());
        AsyncThreadSafeEventService.QueuedAsyncEvent queuedEvent = service.pollNextPendingEvent();
        assertNotNull(queuedEvent);
        VirtualFileSystemLastModifiedEvent event = assertInstanceOf(
                VirtualFileSystemLastModifiedEvent.class, queuedEvent.getEvent());
        assertEquals(200L, event.getLastmodified());
        assertEquals(0, service.getQueueSize());
        assertNull(service.pollNextPendingEvent());
    }

    @Test
    public void testLiveMetadataCancelsStaleRemergeUpdateAndRunsFirst() {
        AsyncThreadSafeEventService service = new AsyncThreadSafeEventService(false);

        withThreadName("RemergeThread - Evo", () -> {
            service.publishAsync(new VirtualFileSystemSizeEvent(inode, "/section/one", 100L));
            service.publishAsync(new VirtualFileSystemSizeEvent(inode, "/section/two", 200L));
        });
        withThreadName("FtpConn thread test", () -> service.publishAsync(
                new VirtualFileSystemSizeEvent(inode, "/section/one", 300L)));

        assertEquals(2, service.getQueueSize());
        AsyncThreadSafeEventService.QueuedAsyncEvent liveEvent = service.pollNextPendingEvent();
        assertNotNull(liveEvent);
        assertEquals("FtpConn thread test", liveEvent.getSourceThreadName());
        assertEquals("/section/one", ((VirtualFileSystemSizeEvent) liveEvent.getEvent())
                .getImmutableInode().getPath());

        AsyncThreadSafeEventService.QueuedAsyncEvent remergeEvent = service.pollNextPendingEvent();
        assertNotNull(remergeEvent);
        assertEquals("RemergeThread - Evo", remergeEvent.getSourceThreadName());
        assertEquals("/section/two", ((VirtualFileSystemSizeEvent) remergeEvent.getEvent())
                .getImmutableInode().getPath());
        assertNull(service.pollNextPendingEvent());
    }

    @Test
    public void testCreateEventSeparatesMetadataUpdatesForTheSamePath() {
        AsyncThreadSafeEventService service = new AsyncThreadSafeEventService(false);

        withThreadName("RemergeThread - Evo", () -> {
            service.publishAsync(new VirtualFileSystemSizeEvent(inode, "/section/release", 100L));
            service.publishAsync(new VirtualFileSystemInodeCreatedEvent(inode, "/section/release"));
            service.publishAsync(new VirtualFileSystemLastModifiedEvent(
                    inode, "/section/release", 200L));
        });

        assertEquals(2, service.getQueueSize());
        assertInstanceOf(VirtualFileSystemInodeCreatedEvent.class,
                service.pollNextPendingEvent().getEvent());
        assertInstanceOf(VirtualFileSystemLastModifiedEvent.class,
                service.pollNextPendingEvent().getEvent());
        assertNull(service.pollNextPendingEvent());
    }

    @Test
    public void testDurationStatsUseAggregates() {
        AsyncThreadSafeEventService.DurationStats stats =
                new AsyncThreadSafeEventService.DurationStats();

        stats.record(10L);
        stats.record(20L);
        stats.record(30L);

        AsyncThreadSafeEventService.DurationSnapshot snapshot = stats.snapshot();
        assertEquals(3L, snapshot.count());
        assertEquals(60L, snapshot.total());
        assertEquals(20.0D, snapshot.mean());
        assertEquals(30L, snapshot.max());
    }

    private static void withThreadName(String threadName, Runnable action) {
        Thread thread = Thread.currentThread();
        String originalName = thread.getName();
        try {
            thread.setName(threadName);
            action.run();
        } finally {
            thread.setName(originalName);
        }
    }
}
