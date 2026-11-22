package org.drftpd.archive.master.archivetypes;

import org.drftpd.master.slavemanagement.RemoteSlave;
import org.drftpd.master.vfs.DirectoryHandle;
import org.drftpd.master.vfs.FileHandle;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ArchiveOffloadScopeTest {
    private MoveReleaseOffSlavesToMostFreeSlaves rule(RemoteSlave source) throws Exception {
        var rule = mock(MoveReleaseOffSlavesToMostFreeSlaves.class, CALLS_REAL_METHODS);
        var field = MoveReleaseOffSlavesToMostFreeSlaves.class.getDeclaredField("_offOfSlaves");
        field.setAccessible(true); field.set(rule, Set.of(source));
        rule._priority = 1; rule._numOfSlaves = 1;
        return rule;
    }

    @Test void onlyQueuesFilesActuallyPresentOnOffloadSources() throws Exception {
        var source = mock(RemoteSlave.class); when(source.getName()).thenReturn("Source");
        var other = mock(RemoteSlave.class); when(other.getName()).thenReturn("Other");
        var dest = mock(RemoteSlave.class); when(dest.getName()).thenReturn("Destination");
        var rule = rule(source);
        var selected = mock(FileHandle.class); when(selected.getSlaves()).thenReturn(Set.of(source, other));
        var untouched = mock(FileHandle.class); when(untouched.getSlaves()).thenReturn(Set.of(other));
        assertNull(rule.createTransferJob(untouched, Set.of(dest)));
        var job = rule.createTransferJob(selected, Set.of(dest));
        assertNotNull(job); assertTrue(job.isSourceRestricted());
        assertTrue(job.isAllowedSource(source)); assertFalse(job.isAllowedSource(other));
    }

    @Test void considersReleaseOffloadedWhenNoSourceCopiesRemainEvenWithoutWholeReleaseAtDestination() throws Exception {
        var source = mock(RemoteSlave.class); var other = mock(RemoteSlave.class);
        var rule = rule(source);
        var dir = mock(DirectoryHandle.class); var file = mock(FileHandle.class);
        when(dir.getInodeHandlesUnchecked()).thenReturn(Set.of(file));
        when(file.getSlaves()).thenReturn(Set.of(other));
        assertTrue(rule.isArchivedDir(dir));
        when(file.getSlaves()).thenReturn(Set.of(source, other));
        assertFalse(rule.isArchivedDir(dir));
    }
}
