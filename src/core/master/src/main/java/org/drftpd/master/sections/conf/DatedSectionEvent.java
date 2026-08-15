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
package org.drftpd.master.sections.conf;

import org.drftpd.master.sections.SectionInterface;
import org.drftpd.master.vfs.DirectoryHandle;
import org.drftpd.master.vfs.LinkHandle;

/**
 * Event published when a dated section creates a new dated directory and updates its now symlink.
 */
public class DatedSectionEvent {

    private final SectionInterface _section;
    private final DirectoryHandle _directory;
    private final LinkHandle _link;
    private final String _date;

    public DatedSectionEvent(SectionInterface section, DirectoryHandle directory, LinkHandle link, String date) {
        _section = section;
        _directory = directory;
        _link = link;
        _date = date;
    }

    public SectionInterface getSection() {
        return _section;
    }

    public DirectoryHandle getDirectory() {
        return _directory;
    }

    public LinkHandle getLink() {
        return _link;
    }

    public String getDate() {
        return _date;
    }
}
