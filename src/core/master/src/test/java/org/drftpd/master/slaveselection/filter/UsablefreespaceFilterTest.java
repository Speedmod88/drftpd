package org.drftpd.master.slaveselection.filter;

import org.drftpd.common.slave.DiskStatus;
import org.drftpd.common.util.Bytes;
import org.drftpd.master.exceptions.NoAvailableSlaveException;
import org.drftpd.master.exceptions.SlaveUnavailableException;
import org.drftpd.master.slavemanagement.RemoteSlave;
import org.drftpd.master.slavemanagement.SlaveStatus;
import org.drftpd.slave.network.Transfer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;

class UsablefreespaceFilterTest {
    private static int sequence;
    private static class SpaceSlave extends RemoteSlave {
        private final SlaveStatus status;
        private boolean full, offline;
        SpaceSlave(SlaveStatus status) { super("space-test-" + sequence++); this.status = status; }
        @Override public SlaveStatus getSlaveStatusAvailable() throws SlaveUnavailableException {
            if (offline) throw new SlaveUnavailableException("offline");
            return status;
        }
        @Override public boolean isUploadBlockedByCapacity() { return full; }
    }

    private SpaceSlave slave(String physical, String usable) {
        return new SpaceSlave(new SlaveStatus(new DiskStatus(
                Bytes.parseBytes(physical), Bytes.parseBytes("400TB"), false, "reported",
                Bytes.parseBytes(usable)), 0, 0, 0, 0, 0, 0));
    }

    private void apply(ScoreChart chart) {
        new UsablefreespaceFilter(1, new Properties()).process(
                chart, null, null, Transfer.TRANSFER_RECEIVING_UPLOAD, null, null);
    }

    @Test void almostFullSlaveIsExcludedDespiteMorePhysicalSpaceAndExistingPreference() throws Exception {
        var evoI = slave("2.5TB", "3.9GB");
        var nw1 = slave("404GB", "304GB");
        var chart = new ScoreChart(List.of(evoI, nw1));
        chart.addScoreToSlave(evoI, 1000000);
        apply(chart);
        assertEquals(nw1, chart.getBestSlave());
        assertEquals(1, chart.getSlaveScores().size());
    }

    @Test void prefersUsableRatherThanPhysicalSpace() throws Exception {
        var low = slave("3TB", "30GB");
        var high = slave("500GB", "350GB");
        var chart = new ScoreChart(List.of(low, high));
        apply(chart);
        assertEquals(high, chart.getBestSlave());
        assertEquals(2, chart.getSlaveScores().size());
    }

    @Test void onlyAllowedLowSpaceDestinationIsNotFalselyReportedFull() throws Exception {
        var only = slave("2.5TB", "3.9GB");
        var chart = new ScoreChart(List.of(only));
        apply(chart);
        assertEquals(only, chart.getBestSlave());
    }

    @Test void allNearReserveDestinationsStillChooseMostUsable() throws Exception {
        var low = slave("2TB", "1GB");
        var high = slave("1TB", "4GB");
        var chart = new ScoreChart(List.of(low, high));
        apply(chart);
        assertEquals(high, chart.getBestSlave());
    }

    @Test void fullAndZeroSpaceSlavesCannotBeReintroducedByFallback() throws Exception {
        var full = slave("2TB", "50GB");
        full.full = true;
        var zero = slave("1TB", "0B");
        var chart = new ScoreChart(List.of(full, zero));
        apply(chart);
        assertThrows(NoAvailableSlaveException.class, chart::getBestSlave);
    }

    @Test void unavailableSlaveIsRemoved() throws Exception {
        var offline = slave("1TB", "100GB");
        offline.offline = true;
        var available = slave("1TB", "100GB");
        var chart = new ScoreChart(List.of(offline, available));
        apply(chart);
        assertEquals(available, chart.getBestSlave());
    }

    @Test void legacyStatusUsesPhysicalSpaceWithoutInventingAReserve() throws Exception {
        var legacy = new SpaceSlave(new SlaveStatus(
                new DiskStatus(Bytes.parseBytes("200GB"), Bytes.parseBytes("1TB")), 0, 0, 0, 0, 0, 0));
        var modern = slave("2TB", "100GB");
        var chart = new ScoreChart(List.of(modern, legacy));
        apply(chart);
        assertEquals(legacy, chart.getBestSlave());
    }

    @Test void boundaryIsInclusiveAndZeroMultiplierKeepsBandwidthScores() throws Exception {
        var props = new Properties(); props.setProperty("1.multiplier", "0");
        var boundary = slave("1TB", "10GB");
        var lower = slave("2TB", "9GB");
        var chart = new ScoreChart(List.of(boundary, lower));
        chart.addScoreToSlave(boundary, -123);
        new UsablefreespaceFilter(1, props).process(chart, null, null,
                Transfer.TRANSFER_RECEIVING_UPLOAD, null, null);
        assertEquals(boundary, chart.getBestSlave());
        assertEquals(-123, chart.getScoreForSlave(boundary).getScore());
    }

    @Test void invalidConfigurationIsRejected() {
        for (String value : List.of("NaN", "Infinity", "-1")) {
            var p = new Properties(); p.setProperty("1.multiplier", value);
            assertThrows(IllegalArgumentException.class, () -> new UsablefreespaceFilter(1, p));
        }
    }
}
