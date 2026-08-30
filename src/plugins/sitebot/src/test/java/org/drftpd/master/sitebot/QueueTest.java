/*
 * This file is part of DrFTPD, Distributed FTP Daemon.
 */
package org.drftpd.master.sitebot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QueueTest {

    @Test
    void sendsHighPriorityMessagesBeforeBulkOutput() {
        Queue queue = new Queue();
        queue.add("bulk-1", MessagePriority.BULK);
        queue.add("command", MessagePriority.COMMAND);
        queue.add("release", MessagePriority.RELEASE);
        queue.add("bulk-2", MessagePriority.BULK);

        assertEquals("release", queue.next());
        assertEquals("command", queue.next());
        assertEquals("bulk-1", queue.next());
        assertEquals("bulk-2", queue.next());
    }

    @Test
    void preservesInsertionOrderInsideOnePriority() {
        Queue queue = new Queue();
        queue.add("first", MessagePriority.RELEASE);
        queue.add("second", MessagePriority.RELEASE);

        assertEquals("first", queue.next());
        assertEquals("second", queue.next());
    }
}
