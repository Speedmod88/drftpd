package org.drftpd.archive.master;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ArchiveControlTest {
    @Test void stopInvalidatesPreviousHandlersEvenAfterResume() {
        ArchiveControl control = new ArchiveControl();
        long old = control.generation();
        assertTrue(control.await(old));
        control.change("stop");
        assertFalse(control.await(old));
        control.change("resume");
        assertFalse(control.await(old));
        assertTrue(control.await(control.generation()));
    }
    @Test void pauseWaitsUntilResume() throws Exception {
        ArchiveControl control = new ArchiveControl();
        control.change("pause");
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var result = executor.submit(() -> control.await(control.generation()));
            assertThrows(java.util.concurrent.TimeoutException.class,
                    () -> result.get(50, java.util.concurrent.TimeUnit.MILLISECONDS));
            control.change("resume");
            assertTrue(result.get(2, java.util.concurrent.TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); }
    }
}
