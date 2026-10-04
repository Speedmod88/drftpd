package org.drftpd.slave.diskselection.filter;

import org.drftpd.common.slave.DiskStatus;
import org.drftpd.slave.vfs.Root;
import org.drftpd.slave.vfs.RootCollection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DiskCapacityTest {
    private DiskSelectionFilter selection;
    private RootCollection roots;
    private Root first;
    private Root second;
    private ArrayList<DiskFilter> filters;

    @BeforeEach
    void setup() {
        first = mock(Root.class);
        second = mock(Root.class);
        when(first.getDiskSpaceAvailable()).thenReturn(100L);
        when(second.getDiskSpaceAvailable()).thenReturn(1000L);
        when(first.getDiskSpaceCapacity()).thenReturn(10000L);
        when(second.getDiskSpaceCapacity()).thenReturn(20000L);
        roots = mock(RootCollection.class);
        when(roots.getRootList()).thenReturn(new ArrayList<>(List.of(first, second)));
        selection = mock(DiskSelectionFilter.class, CALLS_REAL_METHODS);
        when(selection.getRootCollection()).thenReturn(roots);
        filters = new ArrayList<>();
        when(selection.getFilters()).thenReturn(filters);
    }

    private void minimum(int number, String assign, long bytes, String multiplier) {
        Properties p = new Properties();
        p.setProperty(number + ".assign", assign);
        p.setProperty(number + ".minfreespace", bytes + "B");
        p.setProperty(number + ".multiplier", multiplier);
        filters.add(new MinfreespaceFilter(selection, p, number));
    }

    @Test
    void allRootsBelowDifferentThresholdsWarnRegardlessOfAggregateFreeSpace() {
        minimum(1, "1", 200, "1");
        minimum(2, "2", 2000, "1");
        DiskStatus status = selection.getDiskStatus();
        assertTrue(status.isBelowMinimumFreeSpace());
        assertEquals(1100L, status.getBytesAvailable());
        assertEquals(0L, status.getBytesUsable());
        assertEquals(30000L, status.getBytesCapacity());
        assertTrue(status.getMinimumFreeSpaceDetails().contains("root.2"));
        assertNotNull(selection.getBestRoot("/upload"), "multiplier=1 must stay a scoring rule");
    }

    @Test
    void equalityIsFullAndExcludedByHardReserve() {
        minimum(1, "all", 1000, "0");
        assertTrue(selection.getDiskStatus().isBelowMinimumFreeSpace());
        assertEquals(0L, selection.getDiskStatus().getBytesUsable());
        assertNull(selection.getBestRoot("/upload"));
        when(second.getDiskSpaceAvailable()).thenReturn(1001L);
        assertFalse(selection.getDiskStatus().isBelowMinimumFreeSpace());
        assertEquals(1L, selection.getDiskStatus().getBytesUsable());
        assertSame(second, selection.getBestRoot("/upload"));
    }

    @Test
    void unconfiguredRootPreventsWholeSlaveFullWarning() {
        minimum(1, "1", 200, "1");
        assertFalse(selection.getDiskStatus().isBelowMinimumFreeSpace());
        assertTrue(selection.getDiskStatus().hasMinimumFreeSpaceStatus());
        assertEquals(1000L, selection.getDiskStatus().getBytesUsable());
    }

    @Test
    void noRulesReportsUnknown() {
        assertFalse(selection.getDiskStatus().hasMinimumFreeSpaceStatus());
        assertEquals(1100L, selection.getDiskStatus().getBytesUsable());
    }

    @Test
    void highestAssignedReserveIsSubtractedPerRootNotFromAggregate() {
        minimum(1, "all", 200, "0");
        minimum(2, "2", 500, "0");
        assertEquals(1100L, selection.getDiskStatus().getBytesAvailable());
        assertEquals(500L, selection.getDiskStatus().getBytesUsable());
        assertFalse(selection.getDiskStatus().isBelowMinimumFreeSpace());
    }

    @Test
    void removingAllRootsReturnsNullInsteadOfCrashing() {
        minimum(1, "all", 2000, "0");
        filters.add(new CycleFilter(selection, new Properties(), 2));
        assertNull(selection.getBestRoot("/upload"));
        assertTrue(selection.getDiskStatus().isBelowMinimumFreeSpace());
    }

    @Test
    void laterScoringDoesNotReintroduceOrDereferenceRemovedRoot() {
        minimum(1, "1", 200, "0");
        minimum(2, "all", 2000, "1");
        assertSame(second, selection.getBestRoot("/upload"));
    }
}
