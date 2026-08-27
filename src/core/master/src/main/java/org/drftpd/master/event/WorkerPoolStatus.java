/*
 * This file is part of DrFTPD, Distributed FTP Daemon.
 *
 * DrFTPD is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 */
package org.drftpd.master.event;

/**
 * Immutable snapshot of a worker pool for server-status reporting.
 */
public record WorkerPoolStatus(String name, int coreThreads, int maxThreads,
                               int currentThreads, int activeThreads, int queuedTasks) {
}
