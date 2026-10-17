package org.drftpd.slave;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;

class RemoteLogReaderTest {
    @TempDir Path root;
    private Properties config() throws Exception {
        Properties p = new Properties();
        p.setProperty("remote.logs.enabled", "true");
        p.setProperty("remote.logs.root", root.toRealPath().toString());
        return p;
    }
    @Test void disabledByDefaultAndReadOnly() throws Exception {
        assertNotNull(RemoteLogReader.read("1", new String[]{"list"}, new Properties()).getError());
        Files.writeString(root.resolve("slave.log"), "first\nsecond\nlast\n");
        Files.writeString(root.resolve("slave.conf"), "secret");
        var list = RemoteLogReader.read("1", new String[]{"list"}, config());
        assertNull(list.getError());
        assertEquals(java.util.List.of("slave.log"), list.getFiles());
        var tail = RemoteLogReader.read("2", new String[]{"tail", "slave.log", "2"}, config());
        assertEquals("second\nlast", tail.getContent());
        assertNotNull(RemoteLogReader.read("3", new String[]{"delete", "slave.log", "2"}, config()).getError());
        assertTrue(Files.exists(root.resolve("slave.log")));
    }
    @Test void rejectsPathsNonLogsAndInvalidLimits() throws Exception {
        for (String file : java.util.List.of("../slave.log", "/slave.log", "a\\slave.log", "slave.conf", "a.log\n", "C:slave.log")) {
            assertNotNull(RemoteLogReader.read("1", new String[]{"tail", file, "10"}, config()).getError());
        }
        for (String count : java.util.List.of("0", "-1", "2001", "abc")) {
            assertNotNull(RemoteLogReader.read("1", new String[]{"tail", "slave.log", count}, config()).getError());
        }
    }
    @Test void boundsLargeReadsAndFileList() throws Exception {
        Files.writeString(root.resolve("slave.log"), "prefix\n" + "line\n".repeat(100000) + "last\n");
        var result = RemoteLogReader.read("1", new String[]{"tail", "slave.log", "2000"}, config());
        assertNull(result.getError());
        assertTrue(result.getContent().length() <= RemoteLogReader.MAX_BYTES);
        assertEquals(2000, result.getContent().split("\n").length);
        assertTrue(result.getContent().endsWith("last"));
        for (int i=0; i<120; i++) Files.writeString(root.resolve("file" + i + ".log"), "");
        assertEquals(100, RemoteLogReader.read("1", new String[]{"list"}, config()).getFiles().size());
    }
    @Test void rejectsDirectoryAndSymlink() throws Exception {
        Files.createDirectory(root.resolve("directory.log"));
        assertNotNull(RemoteLogReader.read("1", new String[]{"tail", "directory.log", "1"}, config()).getError());
        Path target = Files.writeString(root.resolve("private.conf"), "secret");
        try { Files.createSymbolicLink(root.resolve("linked.log"), target); }
        catch (java.io.IOException | UnsupportedOperationException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "Symlink creation unavailable on this host");
        }
        assertNotNull(RemoteLogReader.read("1", new String[]{"tail", "linked.log", "1"}, config()).getError());
        assertFalse(RemoteLogReader.read("1", new String[]{"list"}, config()).getFiles().contains("linked.log"));
    }
}
