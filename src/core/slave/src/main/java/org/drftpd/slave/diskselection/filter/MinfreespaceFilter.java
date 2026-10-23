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

package org.drftpd.slave.diskselection.filter;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.drftpd.common.util.Bytes;
import org.drftpd.common.util.PropertyHelper;
import org.drftpd.slave.Slave;
import org.drftpd.slave.vfs.Root;

import java.util.Properties;

/**
 * /**
 *
 * <pre>
 *  X.filter=minfreespace
 *  X.multiplier=1
 *  X.minfreespace=1GB
 *  X.assign=1, 2
 * </pre>
 * <p>
 * Works like this: if(diskfree < minfreespace) {
 * addScore( -1 * ( (minfreespace - diskfree) * multiplier) )
 * }
 *
 * @author fr0w
 * @version $Id$
 */
public class MinfreespaceFilter extends DiskFilter {

    private final long _minfreespace;
    private final long recoveryMargin;
    private final java.util.Map<Root, Boolean> blockedRoots = new java.util.HashMap<>();

    private final float _multiplier;

    public static void main(String[] args) {
	System.out.println(Bytes.parseBytes("500GB"));
    }

    public MinfreespaceFilter(DiskSelectionFilter diskSelection, Properties p, Integer i) {
        super(diskSelection, p, i);
        _minfreespace = Bytes.parseBytes(PropertyHelper.getProperty(p, i + ".minfreespace"));
        recoveryMargin = Bytes.parseBytes(p.getProperty(i + ".recovery.margin", "0B"));
        if (_minfreespace < 0 || recoveryMargin < 0 || recoveryMargin > Long.MAX_VALUE - _minfreespace) {
            throw new IllegalArgumentException("Invalid minfreespace/recovery.margin for rule " + i);
        }
        _multiplier = DiskFilter.parseMultiplier(p.getProperty(i + ".multiplier", "0"));
        _assignList = AssignRoot.parseAssign(this, p.getProperty(i + ".assign", "all"));
    }

    public void process(ScoreChart sc, String path) {
        AssignRoot.addScoresToChart(this, _assignList, sc);

        for (Root o : getRootList()) {
            if (!AssignRoot.isAssignedRoot(this, o, _assignList))
                continue;

            long df = o.getDiskSpaceAvailable();

            if (isCapacityBlocked(o, df)) {
                if (_multiplier == 0) {
                    sc.removeFromChart(o);
                } else {
                    sc.addScore(o, -(long) (Math.max(1, _minfreespace - df) * _multiplier));
                }
            }
        }
    }

    public String toString() {
        return getClass().getName() + "[minfreespace=" + _minfreespace + ",roots=" + getAssignList() + "]";
    }

    public Long getMinimumFreeSpace(Root root) {
        return AssignRoot.isAssignedRoot(this, root, _assignList) ? _minfreespace : null;
    }

    public long getRecoveryMargin() {
        return recoveryMargin;
    }

    public synchronized boolean isCapacityBlocked(Root root, long available) {
        if (getMinimumFreeSpace(root) == null) return false;
        long threshold = _minfreespace + (Boolean.TRUE.equals(blockedRoots.get(root)) ? recoveryMargin : 0);
        boolean blocked = available <= threshold;
        blockedRoots.put(root, blocked);
        return blocked;
    }
}
