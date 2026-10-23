package org.drftpd.slave.vfs;

import org.drftpd.slave.Slave;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.FileNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RootCollectionLookupTest {
    @TempDir Path temp;

    @Test void returnsEachPhysicalRootOnceAndRetainsMissingFileFailure() throws Exception {
        Path a = Files.createDirectory(temp.resolve("a"));
        Path b = Files.createDirectory(temp.resolve("b"));
        Root first = new Root(a.toString());
        Root second = new Root(b.toString());
        Slave slave = mock(Slave.class);
        when(slave.rootCollectionThreads()).thenReturn(1);
        RootCollection roots = new RootCollection(slave, List.of(first, second));
        // No directory-listing work is submitted, so the pool never starts a thread.
        assertThrows(FileNotFoundException.class, () -> roots.getMultipleRootsForFile("/file"));
        Files.writeString(a.resolve("file"), "one");
        assertEquals(List.of(first), roots.getMultipleRootsForFile("/file"));
        Files.writeString(b.resolve("file"), "two");
        assertEquals(List.of(first, second), roots.getMultipleRootsForFile("/file"));
        assertEquals(2, roots.getMultipleFiles("/file").size());
    }
}
