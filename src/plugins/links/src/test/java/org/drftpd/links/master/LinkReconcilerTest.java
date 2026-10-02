package org.drftpd.links.master;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class LinkReconcilerTest {
    @Test void burstsAreCoalesced() {
        AtomicInteger calls = new AtomicInteger();
        try (var worker = new LinkReconciler(path -> { calls.incrementAndGet(); return true; })) {
            for (int i = 0; i < 100; i++) worker.request("/release");
            worker.drain();
            worker.drain();
            assertEquals(1, calls.get());
        }
    }
    @Test void unknownGetsBoundedRetriesAndLaterEventsCanRetry() {
        AtomicInteger calls = new AtomicInteger();
        try (var worker = new LinkReconciler(path -> { calls.incrementAndGet(); return false; })) {
            worker.request("/release");
            for (int i = 0; i < 30; i++) worker.drain();
            assertEquals(15, calls.get());
            worker.request("/release");
            worker.drain();
            assertEquals(16, calls.get());
        }
    }
    @Test void exceptionDoesNotStopOtherPathsAndCloseCancelsPending() {
        AtomicInteger calls = new AtomicInteger();
        var worker = new LinkReconciler(path -> {
            calls.incrementAndGet();
            if (path.equals("/broken")) throw new IllegalStateException("test");
            return true;
        });
        worker.request("/broken");
        worker.request("/working");
        worker.drain();
        assertEquals(2, calls.get());
        worker.close();
        worker.drain();
        assertEquals(2, calls.get());
    }
}
