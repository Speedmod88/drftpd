/*
 * This file is part of DrFTPD, Distributed FTP Daemon.
 *
 * DrFTPD is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */
package org.drftpd.master.master;

import org.drftpd.master.GlobalContext;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class GlobalContextTimerTest {

    @Test
    public void testFailingTimerDoesNotStopOtherRegisteredTimers() throws Exception {
        TestGlobalContext context = new TestGlobalContext();
        AtomicInteger failures = new AtomicInteger();
        CountDownLatch successfulRuns = new CountDownLatch(3);

        context.scheduleTimer("test.failure", "test", () -> {
            failures.incrementAndGet();
            throw new IllegalStateException("expected test failure");
        }, 0, 10);
        context.scheduleTimer("test.success", "test", successfulRuns::countDown, 0, 10);

        assertTrue(successfulRuns.await(2, TimeUnit.SECONDS));
        long deadline = System.currentTimeMillis() + 2000;
        while (failures.get() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(failures.get() >= 2);

        GlobalContext.TimerStatus failureStatus = context.getTimerStatuses().stream()
                .filter(status -> status.getName().equals("test.failure"))
                .findFirst()
                .orElseThrow();
        assertNotNull(failureStatus.getLastError());

        context.cancelTimer("test.failure");
        context.cancelTimer("test.success");
    }

    private static class TestGlobalContext extends GlobalContext {
        private TestGlobalContext() {
            _gctx = this;
        }
    }
}
