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
package org.drftpd.common.slave;

import org.drftpd.common.util.Bytes;

import java.io.Serializable;

/**
 * @author zubov
 * @version $Id$
 */
public class DiskStatus implements Serializable {
    private static final long serialVersionUID = 3573098662042584609L;

    private final long _free;

    private final long _total;

    // Optional fields: old peers ignore them; old senders leave details null.
    private final boolean _belowMinimumFreeSpace;
    private final String _minimumFreeSpaceDetails;
    private final Long _usableFree;

    public DiskStatus(long free, long total) {
        this(free, total, false, null);
    }

    public DiskStatus(long free, long total, boolean belowMinimumFreeSpace, String minimumFreeSpaceDetails) {
        this(free, total, belowMinimumFreeSpace, minimumFreeSpaceDetails, null);
    }

    public DiskStatus(long free, long total, boolean belowMinimumFreeSpace,
                      String minimumFreeSpaceDetails, Long usableFree) {
        _free = free;
        _total = total;
        _belowMinimumFreeSpace = belowMinimumFreeSpace;
        _minimumFreeSpaceDetails = minimumFreeSpaceDetails;
        _usableFree = usableFree;
    }

    public boolean hasMinimumFreeSpaceStatus() {
        return _minimumFreeSpaceDetails != null;
    }

    public boolean isBelowMinimumFreeSpace() {
        return hasMinimumFreeSpaceStatus() && _belowMinimumFreeSpace;
    }

    public String getMinimumFreeSpaceDetails() {
        return _minimumFreeSpaceDetails;
    }

    public long getBytesAvailable() {
        return _free;
    }

    /** Display capacity above per-root reserves; raw free space remains unchanged. */
    public long getBytesUsable() {
        if (_usableFree == null) return isBelowMinimumFreeSpace() ? 0 : Math.max(0, _free);
        return Math.max(0, Math.min(_free, _usableFree));
    }

    public long getBytesCapacity() {
        return _total;
    }

    public String toString() {
        return getClass().getName() + "[free="
                + Bytes.formatBytes(getBytesAvailable()) + ",total="
                + Bytes.formatBytes(getBytesCapacity()) + "]";
    }
}
