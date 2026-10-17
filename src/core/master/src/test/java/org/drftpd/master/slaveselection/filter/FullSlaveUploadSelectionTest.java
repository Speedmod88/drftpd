package org.drftpd.master.slaveselection.filter;

import org.drftpd.master.exceptions.NoAvailableSlaveException;
import org.drftpd.master.slavemanagement.RemoteSlave;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FullSlaveUploadSelectionTest {
    private RemoteSlave slave(String name, boolean full) {
        return new RemoteSlave(name) {
            @Override public boolean isUploadBlockedByCapacity() { return full; }
        };
    }

    @Test void uploadsAndJobUploadsExcludeFullEvenWithHigherScore() throws Exception {
        for (String chain : List.of("up", "jobup")) {
            RemoteSlave full = slave("full", true);
            RemoteSlave available = slave("available", false);
            ScoreChart chart = new ScoreChart(List.of(full, available));
            chart.addScoreToSlave(full, 1000000);
            SlaveSelectionManager.excludeFullUploadSlaves(chain, chart);
            assertSame(available, chart.getBestSlave());
        }
    }

    @Test void noFallbackToFullSlave() {
        for (String chain : List.of("up", "jobup")) {
            ScoreChart chart = new ScoreChart(List.of(slave("full", true)));
            assertThrows(NoAvailableSlaveException.class,
                    () -> SlaveSelectionManager.excludeFullUploadSlaves(chain, chart));
        }
    }

    @Test void downloadsStillUseFullSlaves() throws Exception {
        for (String chain : List.of("down", "jobdown")) {
            RemoteSlave full = slave("full", true);
            ScoreChart chart = new ScoreChart(List.of(full));
            SlaveSelectionManager.excludeFullUploadSlaves(chain, chart);
            assertSame(full, chart.getBestSlave());
        }
    }
}
