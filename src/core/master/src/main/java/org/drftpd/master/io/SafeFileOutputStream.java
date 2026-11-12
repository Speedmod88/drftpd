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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @author mog
 * @version $Id$
 */
public class SafeFileOutputStream extends OutputStream {
    private static final Logger logger = LogManager.getLogger(SafeFileOutputStream.class);
    private static final ConcurrentHashMap<Path, LockEntry> locks = new ConcurrentHashMap<>();
    private static final class LockEntry {
        final ReentrantLock lock = new ReentrantLock();
        int users;
    }

    @FunctionalInterface
    public interface WriteOperation {
        void write(OutputStream output) throws IOException;
    }

    private final Path target;
    private final Path temporary;
    private final FileOutputStream output;
    private final boolean allowEmpty;
    private boolean written;
    private boolean failed;
    private boolean closed;

    public SafeFileOutputStream(File file) throws IOException { this(file, false); }

    SafeFileOutputStream(File file, boolean allowEmpty) throws IOException {
        target = file.toPath().toAbsolutePath().normalize();
        this.allowEmpty = allowEmpty;
        Path created = null;
        FileOutputStream opened;
        try {
            String prefix = target.getFileName().toString();
            while (prefix.length() < 3) prefix = "x" + prefix;
            created = Files.createTempFile(target.getParent(), prefix, ".tmp");
            opened = new FileOutputStream(created.toFile());
        } catch (IOException | RuntimeException | Error error) {
            if (created != null) cleanup(created, error);
            throw error;
        }
        temporary = created;
        output = opened;
    }

    public SafeFileOutputStream(String fileName) throws IOException { this(new File(fileName)); }

    /** A serializer closing its view must not commit before its callback succeeds. */
    OutputStream view() {
        return new FilterOutputStream(this) {
            @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                out.write(bytes, offset, length);
            }
            @Override public void close() throws IOException { flush(); }
        };
    }

    /** Only a successful callback may commit; live-object serialization runs without filesystem locks. */
    public static void writeAtomically(File file, WriteOperation operation) throws IOException {
        Objects.requireNonNull(operation);
        try (SafeFileOutputStream stream = new SafeFileOutputStream(file, true)) {
            try { operation.write(stream.view()); }
            catch (IOException | RuntimeException | Error error) {
                try { stream.abort(); }
                catch (IOException cleanupError) { error.addSuppressed(cleanupError); }
                throw error;
            }
        }
    }

    private void checkOpen() throws IOException {
        if (closed) throw new IOException("Safe writer already closed: " + target);
        if (failed) throw new IOException("Previous write failed: " + target);
    }

    @Override public void write(int value) throws IOException {
        checkOpen();
        try { output.write(value); written = true; }
        catch (IOException error) { failed = true; throw error; }
    }

    @Override public void write(byte[] bytes, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        checkOpen();
        try { output.write(bytes, offset, length); written |= length > 0; }
        catch (IOException error) { failed = true; throw error; }
    }

    @Override public void flush() throws IOException {
        checkOpen();
        try { output.flush(); }
        catch (IOException error) { failed = true; throw error; }
    }

    public void abort() throws IOException { failed = true; close(); }

    @Override public void close() throws IOException {
        if (closed) return;
        closed = true;
        boolean retain = false;
        try {
            try (FileOutputStream stream = output) {
                if (!failed) stream.flush();
            }
            if (failed || (!written && !allowEmpty)) {
                Files.deleteIfExists(temporary);
                return;
            }
            // Never unlink the old target or fall back to a non-atomic replacement.
            retain = true;
            LockEntry lease = locks.compute(target, (path, entry) -> {
                if (entry == null) entry = new LockEntry();
                entry.users++;
                return entry;
            });
            lease.lock.lock();
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            finally {
                lease.lock.unlock();
                locks.compute(target, (path, entry) -> --entry.users == 0 ? null : entry);
            }
            retain = false;
        } catch (IOException | RuntimeException | Error error) {
            if (retain) {
                logger.error("Atomic save failed: target={} recoveryTemporary={}; completed temporary file retained, no target deletion attempted",
                        target, temporary, error);
            } else {
                cleanup(temporary, error);
                logger.error("Staged save failed: target={} temporary={}; no target replacement attempted", target, temporary, error);
            }
            throw error;
        }
    }

    private static void cleanup(Path path, Throwable error) {
        try { Files.deleteIfExists(path); }
        catch (IOException | RuntimeException cleanupError) {
            error.addSuppressed(cleanupError);
            logger.error("Unable to clean failed temporary save: {}", path, cleanupError);
        }
    }
}
