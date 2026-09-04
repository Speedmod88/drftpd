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
import org.drftpd.common.slave.LightRemoteInode;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;

/**
 * Walks the slave roots one logical directory at a time and emits children
 * before their parents, which is the ordering expected by the master remerge.
 */
public final class RemergeDirectoryWalker {
    private static final Logger logger = LogManager.getLogger(RemergeDirectoryWalker.class);

    private final List<Root> roots;
    private final int readAttempts;
    private final long retryDelayMillis;
    private final boolean persistentIdentity;

    public RemergeDirectoryWalker(Collection<Root> roots) {
        this(roots, 3, 5000L, false);
    }

    public RemergeDirectoryWalker(Collection<Root> roots, int readAttempts, long retryDelayMillis) {
        this(roots, readAttempts, retryDelayMillis, false);
    }

    public RemergeDirectoryWalker(Collection<Root> roots, int readAttempts, long retryDelayMillis,
                                  boolean persistentIdentity) {
        this.roots = List.copyOf(roots);
        this.readAttempts = Math.max(1, readAttempts);
        this.retryDelayMillis = Math.max(0L, retryDelayMillis);
        this.persistentIdentity = persistentIdentity;
    }

    public boolean walk(String basePath, BooleanSupplier cancelled, DirectoryConsumer consumer)
            throws IOException {
        return walk(basePath, cancelled, (path, directoriesScanned) -> { }, consumer);
    }

    public boolean walk(String basePath, BooleanSupplier cancelled, ProgressConsumer progressConsumer,
                        DirectoryConsumer consumer) throws IOException {
        String normalizedBasePath = normalizePath(basePath);
        List<Path> physicalBasePaths = new ArrayList<>();
        for (Root root : roots) {
            Path physicalRoot = root.getFile().toPath().toAbsolutePath().normalize();
            Path physicalBasePath = normalizedBasePath.isEmpty()
                    ? physicalRoot
                    : physicalRoot.resolve(normalizedBasePath.replace('/', File.separatorChar)).normalize();
            if (!physicalBasePath.startsWith(physicalRoot)) {
                throw new IOException("Remerge path escapes slave root: " + basePath);
            }
            physicalBasePaths.add(physicalBasePath);
        }

        DirectorySnapshot rootSnapshot = readDirectory(
                normalizedBasePath, physicalBasePaths, cancelled);
        if (cancelled.getAsBoolean()) {
            return false;
        }
        if (rootSnapshot == null) {
            throw new FileNotFoundException("Remerge path does not exist on any slave root: " + basePath);
        }
        long directoriesScanned = 1L;
        progressConsumer.accept(rootSnapshot.getPath(), directoriesScanned);

        Deque<DirectoryFrame> stack = new ArrayDeque<>();
        stack.push(new DirectoryFrame(rootSnapshot));

        while (!stack.isEmpty()) {
            if (cancelled.getAsBoolean()) {
                return false;
            }

            DirectoryFrame frame = stack.peek();
            if (frame.children.hasNext()) {
                Map.Entry<String, List<Path>> child = frame.children.next();
                DirectorySnapshot childSnapshot = readDirectory(
                        join(frame.snapshot.relativePath, child.getKey()), child.getValue(), cancelled);
                if (cancelled.getAsBoolean()) {
                    return false;
                }
                if (childSnapshot != null) {
                    progressConsumer.accept(childSnapshot.getPath(), ++directoriesScanned);
                    stack.push(new DirectoryFrame(childSnapshot));
                } else {
                    logger.debug("Directory disappeared during remerge scan: {}/{}",
                            frame.snapshot.protocolPath, child.getKey());
                }
                continue;
            }

            stack.pop();
            if (!consumer.accept(frame.snapshot)) {
                return false;
            }
        }
        return true;
    }

    private DirectorySnapshot readDirectory(String relativePath, Collection<Path> physicalDirectories,
                                            BooleanSupplier cancelled) throws IOException {
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= readAttempts; attempt++) {
            try {
                return readDirectoryOnce(relativePath, physicalDirectories, cancelled);
            } catch (DirectoryIteratorException e) {
                lastFailure = e.getCause();
            } catch (IOException e) {
                lastFailure = e;
            }

            if (cancelled.getAsBoolean()) {
                return null;
            }
            if (attempt < readAttempts) {
                logger.warn("Remerge scan could not read /{} (attempt {}/{}); retrying in {} ms: {}",
                        relativePath, attempt, readAttempts, retryDelayMillis, lastFailure.getMessage());
                try {
                    Thread.sleep(retryDelayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while retrying remerge directory /" + relativePath, e);
                }
            }
        }
        throw new IOException("Remerge scan failed at /" + relativePath + " after "
                + readAttempts + " attempt(s)", lastFailure);
    }

    private DirectorySnapshot readDirectoryOnce(String relativePath, Collection<Path> physicalDirectories,
                                                 BooleanSupplier cancelled) throws IOException {
        BasicFileAttributes directoryAttributes = null;
        Map<String, EntrySnapshot> entries = new TreeMap<>();
        Map<String, List<Path>> childDirectories = new TreeMap<>();

        for (Path physicalDirectory : physicalDirectories) {
            if (cancelled.getAsBoolean()) {
                return null;
            }

            BasicFileAttributes attributes;
            try {
                attributes = Files.readAttributes(
                        physicalDirectory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException e) {
                continue;
            }
            if (!attributes.isDirectory()) {
                continue;
            }
            if (directoryAttributes == null
                    || attributes.lastModifiedTime().compareTo(directoryAttributes.lastModifiedTime()) > 0) {
                directoryAttributes = attributes;
            }

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(physicalDirectory)) {
                for (Path entry : stream) {
                    if (cancelled.getAsBoolean()) {
                        return null;
                    }
                    BasicFileAttributes entryAttributes;
                    try {
                        entryAttributes = Files.readAttributes(
                                entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    } catch (NoSuchFileException e) {
                        logger.debug("Entry disappeared during remerge scan: {}", entry);
                        continue;
                    }

                    if (entryAttributes.isSymbolicLink()) {
                        logger.warn("You have a symbolic link {} -- these are ignored by drftpd", entry);
                        continue;
                    }
                    if (!entryAttributes.isDirectory() && !entryAttributes.isRegularFile()) {
                        continue;
                    }
                    String name = entry.getFileName().toString();
                    if (entryAttributes.isDirectory()) {
                        childDirectories.computeIfAbsent(name, ignored -> new ArrayList<>()).add(entry);
                    }
                    mergeEntry(entries, name, entryAttributes, entry);
                }
            }
        }

        if (directoryAttributes == null) {
            return null;
        }

        List<LightRemoteInode> inodes = new ArrayList<>(entries.size());
        entries.forEach((name, entry) -> {
            BasicFileAttributes attributes = entry.attributes;
            Optional<PersistentInodeIdentity.Identity> identity = persistentIdentity
                    ? PersistentInodeIdentity.read(entry.path) : Optional.empty();
            inodes.add(new LightRemoteInode(
                    name,
                    identity.map(PersistentInodeIdentity.Identity::username)
                            .orElse(persistentIdentity ? "" : "drftpd"),
                    identity.map(PersistentInodeIdentity.Identity::raceGroup)
                            .orElse(persistentIdentity ? "" : "drftpd"),
                    attributes.isDirectory(),
                    attributes.lastModifiedTime().toMillis(),
                    attributes.size()
            ));
        });
        inodes.sort((left, right) -> {
            if (left.isDirectory() != right.isDirectory()) {
                return left.isDirectory() ? -1 : 1;
            }
            int result = String.CASE_INSENSITIVE_ORDER.compare(left.getName(), right.getName());
            return result != 0 ? result : left.getName().compareTo(right.getName());
        });
        Map<String, List<Path>> orderedChildDirectories = new TreeMap<>((left, right) -> {
            int result = String.CASE_INSENSITIVE_ORDER.compare(left, right);
            return result != 0 ? result : left.compareTo(right);
        });
        childDirectories.forEach((name, paths) ->
                orderedChildDirectories.put(name, Collections.unmodifiableList(paths)));

        String protocolPath = relativePath.isEmpty() ? "/" : "/" + relativePath;
        return new DirectorySnapshot(
                relativePath,
                protocolPath,
                Collections.unmodifiableList(inodes),
                Collections.unmodifiableMap(orderedChildDirectories),
                directoryAttributes.lastModifiedTime().toMillis());
    }

    private void mergeEntry(Map<String, EntrySnapshot> entries, String name,
                            BasicFileAttributes attributes, Path physicalEntry) {
        EntrySnapshot existingEntry = entries.get(name);
        BasicFileAttributes existing = existingEntry == null ? null : existingEntry.attributes;
        if (existing == null) {
            entries.put(name, new EntrySnapshot(attributes, physicalEntry));
            return;
        }

        if (attributes.isDirectory()) {
            if (!existing.isDirectory()
                    || attributes.lastModifiedTime().compareTo(existing.lastModifiedTime()) > 0) {
                entries.put(name, new EntrySnapshot(attributes, physicalEntry));
            }
            return;
        }

        if (existing.isDirectory()) {
            logger.warn("File/directory collision detected on slave roots while remerging: {}", physicalEntry);
            return;
        }
        if (existing.isRegularFile()) {
            logger.warn("Duplicate file detected on slave roots while remerging: {}", physicalEntry);
        }
        // Preserve the existing root-order behavior for duplicate regular files.
        entries.put(name, new EntrySnapshot(attributes, physicalEntry));
    }

    private static String normalizePath(String path) throws IOException {
        if (path == null || path.isBlank() || path.equals("/")) {
            return "";
        }

        String normalized = path.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        for (String component : normalized.split("/")) {
            if (component.isEmpty() || component.equals(".") || component.equals("..")) {
                throw new IOException("Invalid remerge path: " + path);
            }
        }
        return normalized;
    }

    private static String join(String parent, String child) {
        return parent.isEmpty() ? child : parent + "/" + child;
    }

    @FunctionalInterface
    public interface DirectoryConsumer {
        boolean accept(DirectorySnapshot snapshot) throws IOException;
    }

    @FunctionalInterface
    public interface ProgressConsumer {
        void accept(String path, long directoriesScanned) throws IOException;
    }

    public static final class DirectorySnapshot {
        private final String relativePath;
        private final String protocolPath;
        private final List<LightRemoteInode> inodes;
        private final Map<String, List<Path>> childDirectories;
        private final long lastModified;

        private DirectorySnapshot(String relativePath, String protocolPath,
                                  List<LightRemoteInode> inodes,
                                  Map<String, List<Path>> childDirectories,
                                  long lastModified) {
            this.relativePath = relativePath;
            this.protocolPath = protocolPath;
            this.inodes = inodes;
            this.childDirectories = childDirectories;
            this.lastModified = lastModified;
        }

        public String getPath() {
            return protocolPath;
        }

        public List<LightRemoteInode> getInodes() {
            return inodes;
        }

        public long getLastModified() {
            return lastModified;
        }
    }

    private static final class DirectoryFrame {
        private final DirectorySnapshot snapshot;
        private final Iterator<Map.Entry<String, List<Path>>> children;

        private DirectoryFrame(DirectorySnapshot snapshot) {
            this.snapshot = snapshot;
            children = snapshot.childDirectories.entrySet().iterator();
        }
    }

    private static final class EntrySnapshot {
        private final BasicFileAttributes attributes;
        private final Path path;

        private EntrySnapshot(BasicFileAttributes attributes, Path path) {
            this.attributes = attributes;
            this.path = path;
        }
    }
}
