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
package org.drftpd.master.io;

import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * @author mog
 * @version $Id$
 */
public class SafeFileWriter extends Writer {
    private final SafeFileOutputStream output;
    private final OutputStreamWriter writer;
    private boolean failed = false;
    private boolean closed;

    public SafeFileWriter(File file) throws IOException {
        output = new SafeFileOutputStream(file, true);
        writer = new OutputStreamWriter(output.view(), StandardCharsets.UTF_8);
    }

    /**
     * @see java.io.File#File(java.lang.String)
     */
    public SafeFileWriter(String fileName) throws IOException {
        this(new File(fileName));
    }

    public void close() throws IOException {
        if (closed) return;
        closed = true;
        try {
            if (failed) output.abort();
            else { writer.close(); output.close(); }
        } catch (IOException | RuntimeException | Error error) {
            try { output.abort(); }
            catch (IOException cleanupError) { error.addSuppressed(cleanupError); }
            throw error;
        }
    }

    public void flush() throws IOException {
        try { writer.flush(); }
        catch (IOException error) { failed = true; throw error; }
    }

    public void write(char[] cbuf, int off, int len) throws IOException {
        try {
            writer.write(cbuf, off, len);
        } catch (IOException e) {
            failed = true;
            throw e;
        }
    }
}
