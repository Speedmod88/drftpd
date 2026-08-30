/*
 * This file is part of DrFTPD, Distributed FTP Daemon.
 */
package org.drftpd.master.sitebot;

/** Priority used by the SiteBot output queue. Lower values are sent first. */
public enum MessagePriority {
    PROTOCOL(0),
    RELEASE(1),
    SLAVE(2),
    ANNOUNCEMENT(3),
    COMMAND(4),
    BULK(5);

    private final int _order;

    MessagePriority(int order) {
        _order = order;
    }

    int getOrder() {
        return _order;
    }
}
