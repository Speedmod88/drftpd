/*
 * This file is part of DrFTPD, Distributed FTP Daemon.
 *
 * DrFTPD is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or any later version.
 */
package org.drftpd.slave.vfs;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.UserDefinedFileAttributeView;
import java.util.Collection;
import java.util.Optional;

/**
 * Stores the FTP owner and race group beside a physical inode. The metadata is
 * deliberately kept in one small xattr so it follows local renames without
 * changing the master/slave serialization format.
 */
public final class PersistentInodeIdentity {
    private static final Logger logger = LogManager.getLogger(PersistentInodeIdentity.class);
    private static final String ATTRIBUTE = "drftpd.identity";
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_ATTRIBUTE_SIZE = 65536;

    private PersistentInodeIdentity() {
    }

    public static boolean isSupported(Collection<Root> roots) {
        for (Root root : roots) {
            Path path = root.getFile().toPath();
            UserDefinedFileAttributeView view = view(path);
            if (view == null) {
                return false;
            }
            try {
                FileStore store = Files.getFileStore(path);
                if (!store.supportsFileAttributeView(UserDefinedFileAttributeView.class)) {
                    return false;
                }

                String probe = ATTRIBUTE + ".probe." + ProcessHandle.current().pid()
                        + "." + Long.toUnsignedString(System.nanoTime());
                view.write(probe, ByteBuffer.wrap("1".getBytes(StandardCharsets.US_ASCII)));
                view.delete(probe);
            } catch (IOException | UnsupportedOperationException | SecurityException e) {
                logger.info("Persistent inode identity is unavailable for slave root {}: {}",
                        path, e.getMessage());
                return false;
            }
        }
        return !roots.isEmpty();
    }

    public static void write(Path path, String username, String raceGroup) throws IOException {
        if (username == null || username.isBlank() || raceGroup == null || raceGroup.isBlank()) {
            return;
        }
        UserDefinedFileAttributeView view = view(path);
        if (view == null) {
            throw new IOException("User-defined file attributes are unavailable for " + path);
        }

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeByte(FORMAT_VERSION);
            output.writeUTF(username);
            output.writeUTF(raceGroup);
        }
        view.write(ATTRIBUTE, ByteBuffer.wrap(bytes.toByteArray()));
    }

    public static void writeIfAbsent(Path path, String username, String raceGroup) throws IOException {
        if (read(path).isEmpty()) {
            write(path, username, raceGroup);
        }
    }

    public static Optional<Identity> read(Path path) {
        UserDefinedFileAttributeView view = view(path);
        if (view == null) {
            return Optional.empty();
        }
        try {
            if (!view.list().contains(ATTRIBUTE)) {
                return Optional.empty();
            }
            int size = view.size(ATTRIBUTE);
            if (size <= 0 || size > MAX_ATTRIBUTE_SIZE) {
                logger.warn("Ignoring invalid persistent inode identity size {} on {}", size, path);
                return Optional.empty();
            }
            ByteBuffer buffer = ByteBuffer.allocate(size);
            view.read(ATTRIBUTE, buffer);
            try (DataInputStream input = new DataInputStream(
                    new ByteArrayInputStream(buffer.array(), 0, buffer.position()))) {
                int version = input.readUnsignedByte();
                if (version != FORMAT_VERSION) {
                    logger.warn("Ignoring unsupported persistent inode identity version {} on {}",
                            version, path);
                    return Optional.empty();
                }
                String username = input.readUTF();
                String raceGroup = input.readUTF();
                if (username.isBlank() || raceGroup.isBlank()) {
                    return Optional.empty();
                }
                return Optional.of(new Identity(username, raceGroup));
            }
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            logger.debug("Unable to read persistent inode identity from {}", path, e);
            return Optional.empty();
        }
    }

    private static UserDefinedFileAttributeView view(Path path) {
        return Files.getFileAttributeView(
                path, UserDefinedFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
    }

    public record Identity(String username, String raceGroup) {
    }
}
