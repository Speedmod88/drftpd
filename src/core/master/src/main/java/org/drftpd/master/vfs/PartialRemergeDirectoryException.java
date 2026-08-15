/*
 * This file is part of DrFTPD, Distributed FTP Daemon.
 *
 * DrFTPD is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * DrFTPD is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with DrFTPD; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.drftpd.master.vfs;

import java.io.IOException;

public class PartialRemergeDirectoryException extends IOException {

    private final String _directory;
    private final String _source;
    private final String _slave;
    private final int _mergeCase;

    public PartialRemergeDirectoryException(String directory, String source, String slave, int mergeCase) {
        super("[" + directory + "][" + slave + "] case " + mergeCase + " - source " + source
                + " is a directory that was not created by an earlier remerge message");
        _directory = directory;
        _source = source;
        _slave = slave;
        _mergeCase = mergeCase;
    }

    public String getDirectory() {
        return _directory;
    }

    public String getSource() {
        return _source;
    }

    public String getSlave() {
        return _slave;
    }

    public int getMergeCase() {
        return _mergeCase;
    }
}
