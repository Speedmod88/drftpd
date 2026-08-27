/*
 * This file is part of DrFTPD, Distributed FTP Daemon.
 */
package org.drftpd.master.event;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeyedAsyncThreadSafeEventServiceTest {

    @Test
    void serializesEventsWithTheSameKey() throws Exception {
        KeyedAsyncThreadSafeEventService service =
                new KeyedAsyncThreadSafeEventService("KeyedTest", 2, 2);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(2);
        List<Integer> processed = new CopyOnWriteArrayList<>();

        service.subscribeStrongly(TestEvent.class, event -> {
            TestEvent testEvent = (TestEvent) event;
            if (testEvent.number() == 1) {
                firstStarted.countDown();
                await(releaseFirst);
            }
            processed.add(testEvent.number());
            completed.countDown();
        });

        service.publishAsync(new TestEvent("release", 1));
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
        service.publishAsync(new TestEvent("release", 2));
        assertFalse(completed.await(100, TimeUnit.MILLISECONDS));

        releaseFirst.countDown();
        assertTrue(completed.await(2, TimeUnit.SECONDS));
        assertEquals(List.of(1, 2), processed);
    }

    @Test
    void processesDifferentKeysConcurrently() throws Exception {
        KeyedAsyncThreadSafeEventService service =
                new KeyedAsyncThreadSafeEventService("KeyedTest", 2, 2);
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(2);

        service.subscribeStrongly(TestEvent.class, event -> {
            started.countDown();
            await(release);
            completed.countDown();
        });

        service.publishAsync(new TestEvent("release-a", 1));
        service.publishAsync(new TestEvent("release-b", 2));

        assertTrue(started.await(2, TimeUnit.SECONDS));
        release.countDown();
        assertTrue(completed.await(2, TimeUnit.SECONDS));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record TestEvent(String key, int number) implements KeyedEvent {
        @Override
        public String getEventKey() {
            return key;
        }
    }
}
