/*
 * This file is part of DrFTPD, Distributed FTP Daemon.
 *
 * DrFTPD is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or any later version.
 */
package org.drftpd.slave.vfs;

import org.drftpd.common.slave.LightRemoteInode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RemergeDirectoryWalkerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    public void testWalksChildrenBeforeParentsAndMergesPhysicalRoots() throws IOException {
        Path firstRoot = Files.createDirectories(temporaryDirectory.resolve("first"));
        Path secondRoot = Files.createDirectories(temporaryDirectory.resolve("second"));
        Files.createDirectories(firstRoot.resolve("show/season"));
        Files.createDirectories(secondRoot.resolve("show/season"));
        Files.createDirectories(secondRoot.resolve("other"));
        Files.writeString(firstRoot.resolve("show/season/episode1.mkv"), "one");
        Files.writeString(secondRoot.resolve("show/season/episode2.mkv"), "two");
        Files.writeString(firstRoot.resolve("show/readme.nfo"), "info");
        Files.writeString(secondRoot.resolve("other/file.txt"), "other");

        RemergeDirectoryWalker walker = new RemergeDirectoryWalker(List.of(
                new Root(firstRoot.toString()), new Root(secondRoot.toString())));
        List<RemergeDirectoryWalker.DirectorySnapshot> snapshots = new ArrayList<>();

        assertTrue(walker.walk("/", () -> false, snapshot -> {
            snapshots.add(snapshot);
            return true;
        }));

        assertBefore(snapshots, "/show/season", "/show");
        assertBefore(snapshots, "/show", "/");
        assertBefore(snapshots, "/other", "/");
        assertEquals(List.of("episode1.mkv", "episode2.mkv"),
                inodeNames(snapshot(snapshots, "/show/season")));
        assertEquals(List.of("season", "readme.nfo"),
                inodeNames(snapshot(snapshots, "/show")));
        assertEquals("/", snapshots.get(snapshots.size() - 1).getPath());
    }

    @Test
    public void testBasePathStillUsesChildFirstOrdering() throws IOException {
        Path root = Files.createDirectories(temporaryDirectory.resolve("root"));
        Files.createDirectories(root.resolve("section/release/Sample"));
        Files.writeString(root.resolve("section/release/file.rar"), "data");

        RemergeDirectoryWalker walker = new RemergeDirectoryWalker(List.of(new Root(root.toString())));
        List<String> paths = new ArrayList<>();

        assertTrue(walker.walk("/section", () -> false, snapshot -> {
            paths.add(snapshot.getPath());
            return true;
        }));

        assertEquals(List.of("/section/release/Sample", "/section/release", "/section"), paths);
    }

    @Test
    public void testConsumerCanStopTraversalAfterFirstResponse() throws IOException {
        Path root = Files.createDirectories(temporaryDirectory.resolve("cancel"));
        Files.createDirectories(root.resolve("a/leaf"));
        Files.createDirectories(root.resolve("b/leaf"));

        RemergeDirectoryWalker walker = new RemergeDirectoryWalker(List.of(new Root(root.toString())));
        List<String> paths = new ArrayList<>();

        assertFalse(walker.walk("/", () -> false, snapshot -> {
            paths.add(snapshot.getPath());
            return false;
        }));
        assertEquals(List.of("/a/leaf"), paths);
    }

    @Test
    public void testEmitsBeforeUnrelatedSubtreesAreScanned() throws IOException {
        Path root = Files.createDirectories(temporaryDirectory.resolve("stream"));
        Files.createDirectories(root.resolve("a/leaf"));
        Files.createDirectories(root.resolve("b"));

        RemergeDirectoryWalker walker = new RemergeDirectoryWalker(List.of(new Root(root.toString())));
        List<RemergeDirectoryWalker.DirectorySnapshot> snapshots = new ArrayList<>();

        assertTrue(walker.walk("/", () -> false, snapshot -> {
            snapshots.add(snapshot);
            if (snapshot.getPath().equals("/a/leaf")) {
                Files.writeString(root.resolve("b/created-during-scan.txt"), "new");
            }
            return true;
        }));

        assertEquals(List.of("created-during-scan.txt"), inodeNames(snapshot(snapshots, "/b")));
    }

    @Test
    public void testReportsScanProgressBeforeChildFirstEmission() throws IOException {
        Path root = Files.createDirectories(temporaryDirectory.resolve("progress"));
        Files.createDirectories(root.resolve("a/leaf"));

        RemergeDirectoryWalker walker = new RemergeDirectoryWalker(List.of(new Root(root.toString())));
        List<String> scanned = new ArrayList<>();
        List<String> emitted = new ArrayList<>();

        assertTrue(walker.walk("/", () -> false,
                (path, directoriesScanned) -> scanned.add(path),
                snapshot -> {
                    emitted.add(snapshot.getPath());
                    return true;
                }));

        assertEquals(List.of("/", "/a", "/a/leaf"), scanned);
        assertEquals(List.of("/a/leaf", "/a", "/"), emitted);
    }

    @Test
    public void testReadsPersistentIdentityWhenEnabled() throws IOException {
        Path root = Files.createDirectories(temporaryDirectory.resolve("identity"));
        Path release = Files.createDirectories(root.resolve("section/release"));
        Path file = Files.writeString(release.resolve("file.rar"), "data");
        Root slaveRoot = new Root(root.toString());
        Assumptions.assumeTrue(PersistentInodeIdentity.isSupported(List.of(slaveRoot)));
        PersistentInodeIdentity.write(file, "uploader", "RACERS");

        RemergeDirectoryWalker walker = new RemergeDirectoryWalker(
                List.of(slaveRoot), 1, 0L, true);
        List<RemergeDirectoryWalker.DirectorySnapshot> snapshots = new ArrayList<>();

        assertTrue(walker.walk("/", () -> false, snapshot -> {
            snapshots.add(snapshot);
            return true;
        }));

        LightRemoteInode inode = snapshot(snapshots, "/section/release").getInodes().stream()
                .filter(candidate -> candidate.getName().equals("file.rar"))
                .findFirst()
                .orElseThrow();
        assertEquals("uploader", inode.getUsername());
        assertEquals("RACERS", inode.getGroup());
    }

    @Test
    public void testMarksMissingPersistentIdentityWithoutLegacyDefaults() throws IOException {
        Path root = Files.createDirectories(temporaryDirectory.resolve("missing-identity"));
        Path release = Files.createDirectories(root.resolve("section/release"));
        Files.writeString(release.resolve("file.rar"), "data");
        Root slaveRoot = new Root(root.toString());

        RemergeDirectoryWalker walker = new RemergeDirectoryWalker(
                List.of(slaveRoot), 1, 0L, true);
        List<RemergeDirectoryWalker.DirectorySnapshot> snapshots = new ArrayList<>();

        assertTrue(walker.walk("/", () -> false, snapshot -> {
            snapshots.add(snapshot);
            return true;
        }));

        LightRemoteInode inode = snapshot(snapshots, "/section/release").getInodes().stream()
                .filter(candidate -> candidate.getName().equals("file.rar"))
                .findFirst()
                .orElseThrow();
        assertEquals("", inode.getUsername());
        assertEquals("", inode.getGroup());
    }

    private static void assertBefore(List<RemergeDirectoryWalker.DirectorySnapshot> snapshots,
                                     String child, String parent) {
        int childIndex = indexOf(snapshots, child);
        int parentIndex = indexOf(snapshots, parent);
        assertTrue(childIndex >= 0, child + " was not emitted");
        assertTrue(parentIndex >= 0, parent + " was not emitted");
        assertTrue(childIndex < parentIndex,
                child + " must be emitted before " + parent);
    }

    private static int indexOf(List<RemergeDirectoryWalker.DirectorySnapshot> snapshots, String path) {
        for (int i = 0; i < snapshots.size(); i++) {
            if (snapshots.get(i).getPath().equals(path)) {
                return i;
            }
        }
        return -1;
    }

    private static RemergeDirectoryWalker.DirectorySnapshot snapshot(
            List<RemergeDirectoryWalker.DirectorySnapshot> snapshots, String path) {
        return snapshots.stream()
                .filter(snapshot -> snapshot.getPath().equals(path))
                .findFirst()
                .orElseThrow();
    }

    private static List<String> inodeNames(RemergeDirectoryWalker.DirectorySnapshot snapshot) {
        return snapshot.getInodes().stream().map(LightRemoteInode::getName).toList();
    }
}
