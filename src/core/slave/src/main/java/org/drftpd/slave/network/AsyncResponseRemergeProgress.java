/*
 * This file is part of DrFTPD, Distributed FTP Daemon.
 *
 * DrFTPD is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or any later version.
 */
package org.drftpd.slave.network;

import org.drftpd.common.network.AsyncResponse;

@SuppressWarnings("serial")
public class AsyncResponseRemergeProgress extends AsyncResponse {
    private final String path;
    private final long directoriesScanned;
    private final long elapsedMillis;

    public AsyncResponseRemergeProgress(String path, long directoriesScanned, long elapsedMillis) {
        super("RemergeProgress");
        this.path = path;
        this.directoriesScanned = directoriesScanned;
        this.elapsedMillis = elapsedMillis;
    }

    public String getPath() {
        return path;
    }

    public long getDirectoriesScanned() {
        return directoriesScanned;
    }

    public long getElapsedMillis() {
        return elapsedMillis;
    }
}
