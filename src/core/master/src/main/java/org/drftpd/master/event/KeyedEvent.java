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
 * Identifies events that must remain ordered relative to other events for the
 * same key while allowing unrelated keys to be processed concurrently.
 */
public interface KeyedEvent {

    String getEventKey();
}
