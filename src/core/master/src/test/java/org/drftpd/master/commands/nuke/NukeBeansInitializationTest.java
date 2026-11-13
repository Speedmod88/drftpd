package org.drftpd.master.commands.nuke;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class NukeBeansInitializationTest {
    @Test void readersWaitForRegistryInitialization() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        Future<NukeBeans> reader;
        try {
            synchronized (NukeBeans.class) {
                reader = executor.submit(() -> {
                    started.countDown();
                    return NukeBeans.getNukeBeans();
                });
                assertTrue(started.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> reader.get(100, TimeUnit.MILLISECONDS));
                NukeBeans.newInstance();
            }
            assertNotNull(reader.get(5, TimeUnit.SECONDS).getNukes());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test void concurrentReloadsNeverExposeUnloadedRegistries() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            var tasks = new ArrayList<Callable<Void>>();
            for (int i = 0; i < 64; i++) {
                final boolean reload = i % 4 == 0;
                tasks.add(() -> {
                    if (reload) NukeBeans.newInstance();
                    NukeBeans beans = NukeBeans.getNukeBeans();
                    assertNotNull(beans.getNukes());
                    assertDoesNotThrow(() -> beans.findPath("/initialization-regression-test"));
                    return null;
                });
            }
            for (Future<Void> result : executor.invokeAll(tasks, 10, TimeUnit.SECONDS)) {
                result.get();
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
