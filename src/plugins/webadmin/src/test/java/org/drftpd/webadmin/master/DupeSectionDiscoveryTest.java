package org.drftpd.webadmin.master;

import org.drftpd.master.indexation.AdvancedSearchParams;
import org.drftpd.master.indexation.IndexEngineInterface;
import org.drftpd.master.vfs.DirectoryHandle;
import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DupeSectionDiscoveryTest {
    @Test void scansPastDefaultAndPageLimitsWithAnExclusiveCursor() throws Exception {
        var index = mock(IndexEngineInterface.class);
        var root = new DirectoryHandle("/DOCS-HD-FRENCH-1080P");
        Map<String, String> paths = new LinkedHashMap<>();
        for (int i = 0; i < 1205; i++) paths.put(root.getPath() + "/Doc." + String.format("%04d", i) + "-GRP", "d");
        when(index.advancedFind(eq(root), any(), eq("WEBADMIN-DUPE2"))).thenAnswer(call -> {
            AdvancedSearchParams params = call.getArgument(1);
            assertEquals(WebAdminDupe.DISCOVERY_PAGE_SIZE, params.getLimit());
            assertEquals("fullPath", params.getSortField());
            assertEquals(AdvancedSearchParams.InodeType.DIRECTORY, params.getInodeType());
            Map<String, String> page = new LinkedHashMap<>();
            paths.entrySet().stream().filter(e -> e.getKey().compareTo(params.getAfterPath()) > 0)
                    .limit(params.getLimit()).forEach(e -> page.put(e.getKey(), e.getValue()));
            return page;
        });
        var result = WebAdminDupe.indexedDirectoryPaths(root, index);
        assertEquals(1205, result.size());
        assertEquals(1205, result.stream().distinct().count());
        assertTrue(result.contains(root.getPath() + "/Doc.1204-GRP"));
        verify(index, times(3)).advancedFind(eq(root), any(), eq("WEBADMIN-DUPE2"));
    }

    @Test void excludesSiblingSectionsAndFilesEvenIfIndexReturnsThem() throws Exception {
        var index = mock(IndexEngineInterface.class);
        var root = new DirectoryHandle("/DOCS-HD-FRENCH-720P");
        when(index.advancedFind(eq(root), any(), anyString())).thenReturn(Map.of(
                "/DOCS-HD-FRENCH-1080P/Other-GRP/", "d",
                "/DOCS-HD-FRENCH-720P-old/Other-GRP/", "d",
                "/DOCS-HD-FRENCH-720P/Doc-GRP/", "d",
                "/DOCS-HD-FRENCH-720P/Doc-GRP/file.sfv", "f"), Map.of());
        assertEquals(java.util.List.of("/DOCS-HD-FRENCH-720P/Doc-GRP"),
                WebAdminDupe.indexedDirectoryPaths(root, index));
    }

    @Test void failsRatherThanLoopingIfCursorDoesNotAdvance() throws Exception {
        var index = mock(IndexEngineInterface.class);
        var root = new DirectoryHandle("/DOCS-HD-FRENCH-1080P");
        when(index.advancedFind(eq(root), any(), anyString())).thenReturn(Map.of(root.getPath() + "/", "d"));
        assertThrows(java.io.IOException.class, () -> WebAdminDupe.indexedDirectoryPaths(root, index));
    }
}
