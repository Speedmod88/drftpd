/*
 * This file is part of DrFTPD, Distributed FTP Daemon.
 */
package org.drftpd.master.master;

import org.drftpd.master.GlobalContext;
import org.drftpd.master.event.WorkerPoolStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobalContextWorkerPoolStatusTest {

    @Test
    void includesRegisteredPluginWorkerPools() {
        String id = "test.worker.pool";
        GlobalContext.registerWorkerPoolStatus(id,
                () -> new WorkerPoolStatus("Test workers", 1, 2, 1, 0, 3));
        try {
            assertTrue(GlobalContext.getWorkerPoolStatuses().stream()
                    .anyMatch(status -> status.name().equals("Test workers") && status.queuedTasks() == 3));
        } finally {
            GlobalContext.unregisterWorkerPoolStatus(id);
        }
    }
}
