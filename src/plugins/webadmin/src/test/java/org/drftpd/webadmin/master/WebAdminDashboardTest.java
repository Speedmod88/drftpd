package org.drftpd.webadmin.master;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Properties;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class WebAdminDashboardTest {
    @Test void configAccessRejectsTraversalBackupsAndNonConfigFiles(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        java.nio.file.Files.writeString(root.resolve("slave.conf"), "name=EvoI");
        java.nio.file.Files.writeString(root.resolve("secret.key"), "secret");
        java.nio.file.Files.createDirectories(root.resolve(".webadmin-backups"));
        java.nio.file.Files.writeString(root.resolve(".webadmin-backups/slave.conf"), "old");
        WebAdminServer server = new WebAdminServer(new WebAdminSettings(new Properties()));
        try {
            var method = WebAdminServer.class.getDeclaredMethod("safeConfigFile", java.nio.file.Path.class, String.class);
            method.setAccessible(true);
            assertEquals(root.resolve("slave.conf"), method.invoke(server, root, "slave.conf"));
            for (String path : List.of("../slave.conf", "secret.key", ".webadmin-backups/slave.conf",
                    root.resolve("slave.conf").toString())) {
                var error = assertThrows(java.lang.reflect.InvocationTargetException.class,
                        () -> method.invoke(server, root, path));
                assertTrue(error.getCause() instanceof RuntimeException);
            }
        } finally { server.stop(); }
    }
    @Test void parsesAllThreeLoadAverages() {
        assertArrayEquals(new double[] {1.2, 3.4, 5.6},
                WebAdminMetrics.parseLoad("1.20 3.40 5.60 2/120 123"));
    }
    @Test void rejectsInvalidLoadAverages() {
        for (String value : List.of("", "1 2", "NaN 2 3", "-1 2 3", "Infinity 2 3")) {
            assertThrows(IllegalArgumentException.class, () -> WebAdminMetrics.parseLoad(value));
        }
    }
    @Test void snapshotHasCpuMemoryAndThreadMetrics() {
        var result = WebAdminMetrics.snapshot();
        for (String key : List.of("systemCpu", "processCpu", "memoryTotalBytes", "memoryFreeBytes",
                "loadAverages", "threads", "sampledAt")) assertTrue(result.containsKey(key));
    }
    @Test void onlyOfferedDuplicatePathsCanBeSelected() {
        assertEquals(List.of("/movies/Film-GRP"),
                WebAdminDupe.selection(List.of("/movies/Film-GRP"),
                        List.of("/movies/Film-GRP", "/movies/Film-GRP")));
        assertThrows(IllegalArgumentException.class,
                () -> WebAdminDupe.selection(List.of("/movies/Film-GRP"), List.of("/movies/Keeper-GRP")));
        assertThrows(IllegalArgumentException.class,
                () -> WebAdminDupe.selection(List.of("/"), List.of("/")));
    }
    @Test void selectionRejectsEmptyOversizedAndInjectedPaths() {
        assertThrows(IllegalArgumentException.class, () -> WebAdminDupe.selection(List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> WebAdminDupe.selection(List.of(), null));
        List<String> large = IntStream.range(0, 101).mapToObj(i -> "/movies/Film" + i).toList();
        assertThrows(IllegalArgumentException.class, () -> WebAdminDupe.selection(large, large));
        for (String path : List.of("/../movies", "/movies/\nSITE WIPE /", "/movies/../private")) {
            assertThrows(IllegalArgumentException.class, () -> WebAdminDupe.selection(List.of(path), List.of(path)));
        }
    }
    @Test void mountedSlaveScopesAreExplicitAndIndependent() {
        Properties props = new Properties();
        props.setProperty("slave.1.name", "EvoI");
        props.setProperty("slave.1.config.root", "mounted/EvoI/config");
        props.setProperty("slave.1.logs.root", "mounted/EvoI/logs");
        props.setProperty("slave.2.name", "EvoFAST");
        props.setProperty("slave.2.logs.root", "mounted/EvoFAST/logs");
        WebAdminSettings settings = new WebAdminSettings(props);
        assertEquals(2, settings.slaveFiles.size());
        assertNotEquals(settings.configRoot, settings.slaveFiles.get("EvoI").config());
        assertNull(settings.slaveFiles.get("EvoFAST").config());
        assertTrue(new WebAdminSettings(new Properties()).slaveFiles.isEmpty());
    }
    @Test void invalidScopeNamesAndBlankRootsAreRejected() {
        for (String name : List.of("", "master")) {
            Properties props = new Properties();
            props.setProperty("slave.1.name", name);
            assertThrows(IllegalArgumentException.class, () -> new WebAdminSettings(props));
        }
        Properties props = new Properties();
        props.setProperty("slave.1.name", "EvoI");
        props.setProperty("slave.1.logs.root", " ");
        assertThrows(IllegalArgumentException.class, () -> new WebAdminSettings(props));
    }
    @Test void explicitCommandAllowlistIsNotExpandedSilently() {
        Properties props = new Properties();
        props.setProperty("command.allowed.1", "site dupe2");
        assertEquals(java.util.Set.of("site dupe2"), new WebAdminSettings(props).allowedCommands);
        assertTrue(new WebAdminSettings(new Properties()).allowedCommands.contains("site swho"));
    }
}
