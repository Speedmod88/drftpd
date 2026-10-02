package org.drftpd.links.master;

import org.drftpd.links.master.types.sfvmissing.SFVMissing;
import org.drftpd.master.vfs.DirectoryHandle;
import org.drftpd.master.vfs.FileHandle;
import org.junit.jupiter.api.Test;
import java.util.Properties;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SFVMissingRecheckTest {
    @Test void delayedMkdRemovesMarkerInsteadOfRecreatingIt() throws Exception {
        DirectoryHandle directory = mock(DirectoryHandle.class);
        FileHandle sfv = mock(FileHandle.class);
        when(sfv.getName()).thenReturn("release.SFV");
        when(directory.getFilesUnchecked()).thenReturn(Set.of(sfv));
        Properties props = new Properties();
        props.setProperty("1.linkname", "(no_sfv)-${dirname}");
        boolean[] deleted = {false};
        SFVMissing link = new SFVMissing(props, 1, "sfvmissing") {
            @Override public void doDeleteLink(DirectoryHandle target) {
                assertSame(directory, target);
                deleted[0] = true;
            }
        };
        link.doCreateLink(directory);
        assertTrue(deleted[0]);
        verify(sfv, never()).getCheckSum();
    }
}
