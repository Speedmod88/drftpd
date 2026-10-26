package org.drftpd.webadmin.master;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.drftpd.master.exceptions.NoAvailableSlaveException;
import org.drftpd.master.network.BaseFtpConnection;
import org.drftpd.master.slavemanagement.RemoteSlave;
import org.drftpd.master.slaveselection.filter.FilterChain;
import org.drftpd.master.slaveselection.filter.ScoreChart;
import org.drftpd.master.vfs.FileHandle;
import org.drftpd.slave.network.Transfer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SrrdbUploadSelectionTest {
    private final FilterChain rules = mock(FilterChain.class);
    private final BaseFtpConnection session = mock(BaseFtpConnection.class);
    private final FileHandle target = new FileHandle("/SECTION/Release-GRP/file.sfv");

    private RemoteSlave slave(String name) {
        RemoteSlave slave = mock(RemoteSlave.class);
        when(slave.getName()).thenReturn(name);
        when(slave.isAvailable()).thenReturn(true);
        when(slave.getRenameQueue()).thenReturn(new ConcurrentLinkedDeque<>());
        return slave;
    }

    private RemoteSlave select(List<RemoteSlave> slaves, Set<String> holders) throws Exception {
        return SrrdbUploadSelection.select(slaves, holders, rules, session, target);
    }

    @Test void prefersEligibleHolderAfterApplyingUploadRules() throws Exception {
        RemoteSlave holder = slave("Holder"), other = slave("Other");
        doAnswer(call -> {
            ScoreChart chart = call.getArgument(0);
            chart.addScoreToSlave(other, 100);
            return null;
        }).when(rules).filter(any(), same(session), eq(Transfer.TRANSFER_RECEIVING_UPLOAD), same(target), isNull());
        assertSame(holder, select(List.of(other, holder), Set.of("Holder")));
        verify(rules).filter(any(), same(session), eq(Transfer.TRANSFER_RECEIVING_UPLOAD), same(target), isNull());
        verify(holder).setLastDirection(eq(Transfer.TRANSFER_RECEIVING_UPLOAD), anyLong());
    }

    @Test void fallsBackFromRemergingHolderUsingUploadScores() throws Exception {
        RemoteSlave holder = slave("Holder"), first = slave("First"), best = slave("Best");
        when(holder.isRemerging()).thenReturn(true);
        doAnswer(call -> {
            ScoreChart chart = call.getArgument(0);
            assertEquals(2, chart.getSlaveScores().size());
            chart.addScoreToSlave(best, 50);
            return null;
        }).when(rules).filter(any(), any(), anyChar(), any(), isNull());
        assertSame(best, select(List.of(holder, first, best), Set.of("Holder")));
    }

    @Test void routingExclusionOverridesHolderPreference() throws Exception {
        RemoteSlave holder = slave("Holder"), other = slave("Other");
        doAnswer(call -> {
            ((ScoreChart) call.getArgument(0)).removeSlaveFromChart(holder);
            return null;
        }).when(rules).filter(any(), any(), anyChar(), any(), isNull());
        assertSame(other, select(List.of(holder, other), Set.of("Holder")));
    }

    @Test void permitsDirectoryWithNoSlaveReferences() throws Exception {
        RemoteSlave other = slave("Other");
        assertSame(other, select(List.of(other), Set.of()));
    }

    @Test void rejectsOfflineFullRemergingAndQueuedSlaves() throws Exception {
        RemoteSlave offline = slave("Offline"), full = slave("Full"), merging = slave("Merging"), queued = slave("Queued");
        when(offline.isAvailable()).thenReturn(false);
        when(full.isUploadBlockedByCapacity()).thenReturn(true);
        when(merging.isRemerging()).thenReturn(true);
        ConcurrentLinkedDeque queue = mock(ConcurrentLinkedDeque.class);
        when(queued.getRenameQueue()).thenReturn(queue);
        assertThrows(NoAvailableSlaveException.class,
                () -> select(List.of(offline, full, merging, queued), Set.of("Full")));
        verifyNoInteractions(rules);
    }

    @Test void rechecksCapacityAfterFiltering() throws Exception {
        RemoteSlave full = slave("Full"), other = slave("Other");
        doAnswer(call -> {
            when(full.isUploadBlockedByCapacity()).thenReturn(true);
            return null;
        }).when(rules).filter(any(), any(), anyChar(), any(), isNull());
        assertSame(other, select(List.of(full, other), Set.of("Full")));
    }

    @Test void doesNotBypassAnEmptyRoutingResult() throws Exception {
        RemoteSlave other = slave("Other");
        doAnswer(call -> {
            ((ScoreChart) call.getArgument(0)).getSlaveScores().clear();
            return null;
        }).when(rules).filter(any(), any(), anyChar(), any(), isNull());
        assertThrows(NoAvailableSlaveException.class, () -> select(List.of(other), Set.of()));
    }
}
