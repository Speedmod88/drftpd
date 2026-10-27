package org.drftpd.webadmin.master;

import org.drftpd.master.exceptions.NoAvailableSlaveException;
import org.drftpd.master.network.BaseFtpConnection;
import org.drftpd.master.slavemanagement.RemoteSlave;
import org.drftpd.master.slaveselection.filter.FilterChain;
import org.drftpd.master.slaveselection.filter.ScoreChart;
import org.drftpd.master.vfs.FileHandle;
import org.drftpd.slave.network.Transfer;
import java.util.Collection;
import java.util.Set;

/** Recovery uses normal upload rules, without requiring an existing physical copy. */
final class SrrdbUploadSelection {
    private SrrdbUploadSelection() { }

    static boolean eligible(RemoteSlave slave) {
        return slave.isAvailable() && !slave.isRemerging()
                && !slave.isUploadBlockedByCapacity() && slave.getRenameQueue().isEmpty();
    }

    static RemoteSlave select(Collection<RemoteSlave> slaves, Set<String> holders,
                              FilterChain uploadRules, BaseFtpConnection session, FileHandle target)
            throws NoAvailableSlaveException {
        ScoreChart chart = new ScoreChart(slaves.stream().filter(SrrdbUploadSelection::eligible).toList());
        if (chart.isEmpty()) throw unavailable();
        uploadRules.filter(chart, session, Transfer.TRANSFER_RECEIVING_UPLOAD, target, null);
        // Capacity/connection state can change while filters query the slaves.
        chart.getSlaveScores().removeIf(score -> !eligible(score.getRSlave()));
        if (chart.isEmpty()) throw unavailable();
        RemoteSlave result = chart.getBestSlave();
        result.setLastDirection(Transfer.TRANSFER_RECEIVING_UPLOAD, System.currentTimeMillis());
        return result;
    }

    private static NoAvailableSlaveException unavailable() {
        return new NoAvailableSlaveException("No eligible recovery upload slave: candidates are offline, "
                + "remerging, FULL, have queued operations, or are excluded by slaveselection-up.conf");
    }
}
