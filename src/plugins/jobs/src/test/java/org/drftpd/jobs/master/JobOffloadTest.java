package org.drftpd.jobs.master;

import org.drftpd.master.exceptions.NoAvailableSlaveException;
import org.drftpd.master.slavemanagement.RemoteSlave;
import org.drftpd.master.vfs.FileHandle;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JobOffloadTest {
    private JobManager manager(Job... jobs) throws Exception {
        var manager = new JobManager();
        var field = JobManager.class.getDeclaredField("_queuedJobSet");
        field.setAccessible(true); field.set(manager, new LinkedHashSet<>(List.of(jobs)));
        return manager;
    }
    private RemoteSlave slave(String name) {
        var slave = mock(RemoteSlave.class);
        when(slave.getName()).thenReturn(name);
        when(slave.isAvailable()).thenReturn(true);
        return slave;
    }
    private FileHandle file(Set<RemoteSlave> copies) throws Exception {
        var file = mock(FileHandle.class);
        when(file.getPath()).thenReturn("/SECTION/Release-GRP/file.rar");
        when(file.getSlaves()).thenAnswer(call -> new HashSet<>(copies));
        when(file.getAvailableSlaves()).thenAnswer(call -> copies.stream().filter(RemoteSlave::isAvailable).toList());
        doAnswer(call -> { copies.remove(call.getArgument(0)); return null; }).when(file).removeSlave(any());
        return file;
    }

    @Test void deletesOnlyConfiguredSourcesAndPreservesAllOtherCopies() throws Exception {
        var source = slave("Source"); var other = slave("Other"); var dest = slave("Destination");
        var file = file(new HashSet<>(Set.of(source, other, dest)));
        var job = Job.forOffload(file, 1, 1, Set.of(dest), Set.of(source));
        job.sentToSlave(dest);
        assertTrue(job.isDone());
        assertEquals(Set.of(other, dest), file.getSlaves());
        verify(source).simpleDelete(file.getPath());
        verify(other, never()).simpleDelete(anyString());
        verify(dest, never()).simpleDelete(anyString());
    }

    @Test void waitsForEnoughOnlineDestinationCopiesBeforeDeletingSources() throws Exception {
        var source = slave("Source"); var dest1 = slave("D1"); var dest2 = slave("D2");
        Set<RemoteSlave> copies = new HashSet<>(Set.of(source, dest1));
        var file = file(copies);
        var job = Job.forOffload(file, 1, 2, Set.of(dest1, dest2), Set.of(source));
        job.cleanup();
        verify(source, never()).simpleDelete(anyString());
        copies.add(dest2);
        when(dest2.isAvailable()).thenReturn(false);
        job.cleanup();
        verify(source, never()).simpleDelete(anyString());
        when(dest2.isAvailable()).thenReturn(true);
        job.cleanup();
        verify(source).simpleDelete(file.getPath());
        assertTrue(job.isDone());
    }

    @Test void restrictedSourceAvailabilityAndTransferGuardNeverFallBackToOtherHolders() throws Exception {
        var source = slave("Source"); var other = slave("Other"); var dest = slave("Destination");
        var file = file(new HashSet<>(Set.of(source, other)));
        var job = Job.forOffload(file, 1, 1, Set.of(dest), Set.of(source));
        assertEquals(Set.of(source), new HashSet<>(job.getAvailableSourceSlaves()));
        assertFalse(job.isAllowedSource(other));
        assertThrows(IllegalArgumentException.class, () -> job.transfer(true, true, other, dest));
        when(source.isAvailable()).thenReturn(false);
        assertThrows(NoAvailableSlaveException.class, job::getAvailableSourceSlaves);
        verify(other, never()).simpleDelete(anyString());
    }

    @Test void offlineRemergingAndActiveSourcesStayPendingWithoutDeletion() throws Exception {
        var source = slave("Source"); var dest = slave("Destination");
        var file = file(new HashSet<>(Set.of(source, dest)));
        var job = Job.forOffload(file, 1, 1, Set.of(dest), Set.of(source));
        when(source.isAvailable()).thenReturn(false);
        job.cleanup(); assertFalse(job.isDone());
        when(source.isAvailable()).thenReturn(true);
        when(source.isRemerging()).thenReturn(true);
        job.cleanup(); assertFalse(job.isDone());
        when(source.isRemerging()).thenReturn(false);
        when(file.isTransferring()).thenReturn(true);
        job.cleanup(); assertFalse(job.isDone());
        verify(source, never()).simpleDelete(anyString());
        when(file.isTransferring()).thenReturn(false);
        job.cleanup(); assertTrue(job.isDone());
    }

    @Test void goneSourceNeedsNoTransferAndLegacyConsolidationIsUnchanged() throws Exception {
        var source = slave("Source"); var other = slave("Other"); var dest = slave("Destination");
        var file = file(new HashSet<>(Set.of(other)));
        var job = Job.forOffload(file, 1, 1, Set.of(dest), Set.of(source));
        assertTrue(job.checkIfArchived()); job.cleanup(); assertTrue(job.isDone());
        verify(other, never()).simpleDelete(anyString());
        var legacyFile = file(new HashSet<>(Set.of(source, other, dest)));
        var legacy = new Job(legacyFile, 1, 1, Set.of(dest));
        assertFalse(legacy.isSourceRestricted());
        legacy.sentToSlave(dest);
        assertEquals(Set.of(dest), legacyFile.getSlaves());
        verify(other).simpleDelete(legacyFile.getPath());
    }

    @Test void validatesScopesAndUsesDefensiveSourceSnapshot() throws Exception {
        var source = slave("Source"); var dest = slave("Destination");
        var file = file(new HashSet<>(Set.of(source)));
        assertThrows(IllegalArgumentException.class, () -> Job.forOffload(file, 1, 1, Set.of(dest), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> Job.forOffload(file, 1, 2, Set.of(dest), Set.of(source)));
        assertThrows(IllegalArgumentException.class, () -> Job.forOffload(file, 1, 1, Set.of(dest), Set.of(dest)));
        Set<RemoteSlave> sources = new HashSet<>(Set.of(source));
        var job = Job.forOffload(file, 1, 1, Set.of(dest), sources);
        sources.clear(); assertTrue(job.isAllowedSource(source));
    }

    @Test void selectionExcludesUnrelatedHoldersAndCleansAllConfiguredSourcesOnly() throws Exception {
        var source1 = slave("S1"); var source2 = slave("S2");
        var other = slave("Other"); var dest = slave("Destination");
        Set<RemoteSlave> copies = new HashSet<>(Set.of(source1, source2, other));
        var file = file(copies);
        var job = spy(Job.forOffload(file, 1, 1, Set.of(dest), Set.of(source1, source2)));
        doReturn(List.of(dest)).when(job).getSlaveObjects(anyCollection());
        assertEquals(Set.of(dest, other), new HashSet<>(job.getDownloadExclusions()));
        copies.add(dest);
        job.cleanup();
        assertEquals(Set.of(dest, other), file.getSlaves());
        verify(source1).simpleDelete(file.getPath()); verify(source2).simpleDelete(file.getPath());
        verify(other, never()).simpleDelete(anyString());
    }

    @Test void offlineSourceDoesNotBlockUnrelatedJobsOrPermitAnotherSource() throws Exception {
        var source = slave("Source"); var other = slave("Other"); var dest = slave("Destination");
        when(source.isAvailable()).thenReturn(false);
        var job = Job.forOffload(file(new HashSet<>(Set.of(source, other))), 1, 1, Set.of(dest), Set.of(source));
        assertNull(manager(job).getNextJob(Set.of(), Set.of()));
        var unrelated = new Job(file(new HashSet<>(Set.of(other))), 1, 1, Set.of(dest));
        assertSame(unrelated, manager(job, unrelated).getNextJob(Set.of(), Set.of()));
    }

    @Test void deferredCleanupRemainsQueuedUntilSourceReturns() throws Exception {
        var source = slave("Source"); var other = slave("Other"); var dest = slave("Destination");
        var file = file(new HashSet<>(Set.of(source, other, dest)));
        var job = spy(Job.forOffload(file, 1, 1, Set.of(dest), Set.of(source)));
        doReturn(List.of(dest)).when(job).getSlaveObjects(anyCollection());
        var manager = manager(job);
        var global = mock(org.drftpd.master.GlobalContext.class, RETURNS_DEEP_STUBS);
        when(global.getSlaveManager().getAvailableSlaves()).thenAnswer(call -> new ArrayList<>(List.of(dest, other)));
        try (var context = mockStatic(org.drftpd.master.GlobalContext.class)) {
            context.when(org.drftpd.master.GlobalContext::getGlobalContext).thenReturn(global);
            when(source.isAvailable()).thenReturn(false);
            manager.processJob();
            assertTrue(manager.getAllJobsFromQueue().contains(job)); assertFalse(job.isDone());
            verify(source, never()).simpleDelete(anyString());
            when(source.isAvailable()).thenReturn(true);
            manager.processJob();
            assertTrue(manager.getAllJobsFromQueue().isEmpty()); assertTrue(job.isDone());
            verify(source).simpleDelete(file.getPath());
            verify(other, never()).simpleDelete(anyString());
            verify(job, never()).getDownloadExclusions();
        }
    }
}
