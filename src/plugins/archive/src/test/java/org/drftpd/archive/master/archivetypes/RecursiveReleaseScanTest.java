package org.drftpd.archive.master.archivetypes;

import org.drftpd.common.dynamicdata.KeyNotFoundException;
import org.drftpd.master.commands.nuke.metadata.NukeData;
import org.drftpd.master.vfs.DirectoryHandle;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RecursiveReleaseScanTest {
    private DirectoryHandle directory(String path, DirectoryHandle parent) throws Exception {
        DirectoryHandle dir = mock(DirectoryHandle.class);
        when(dir.getPath()).thenReturn(path);
        when(dir.getName()).thenReturn(path.substring(path.lastIndexOf('/') + 1));
        when(dir.getParent()).thenReturn(parent);
        when(dir.getPluginMetaData(NukeData.NUKEDATA)).thenThrow(new KeyNotFoundException());
        return dir;
    }

    @Test void walksMixedDepthsButNeverDescendsIntoReleasesOrLinks() throws Exception {
        DirectoryHandle root = directory("/archive", null);
        when(root.isRoot()).thenReturn(true);
        DirectoryHandle container = directory("/archive/TV-FR", root);
        DirectoryHandle deep = directory("/archive/TV-FR/Show", container);
        DirectoryHandle first = directory("/archive/Show.E01.FRENCH.WEB-GRP", root);
        DirectoryHandle second = directory("/archive/TV-FR/Show/Show.E02.FRENCH.WEB-GRP", deep);
        DirectoryHandle link = directory("/archive/link", root);
        when(link.isLink()).thenReturn(true);
        when(root.getInodeHandlesUnchecked()).thenReturn(Set.of(container, first, link));
        when(container.getInodeHandlesUnchecked()).thenReturn(Set.of(deep));
        when(deep.getInodeHandlesUnchecked()).thenReturn(Set.of(second));
        var scan = ReleaseOrganization.releases(root, null);
        assertSame(first, scan.next());
        assertSame(second, scan.next());
        assertFalse(scan.hasNext());
        verify(first, never()).getInodeHandlesUnchecked();
        verify(second, never()).getInodeHandlesUnchecked();
        verify(link, never()).getInodeHandlesUnchecked();
    }
}
