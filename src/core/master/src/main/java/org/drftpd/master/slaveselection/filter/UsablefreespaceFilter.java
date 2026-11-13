package org.drftpd.master.slaveselection.filter;

import org.drftpd.common.util.Bytes;
import org.drftpd.common.vfs.InodeHandleInterface;
import org.drftpd.master.exceptions.SlaveUnavailableException;
import org.drftpd.master.slavemanagement.RemoteSlave;
import org.drftpd.master.usermanager.User;

import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/** Opt-in upload scoring using headroom above the slave's own disk reserves. */
public class UsablefreespaceFilter extends Filter {
    private final long minimum;
    private final double multiplier;

    public UsablefreespaceFilter(int i, Properties properties) {
        super(i, properties);
        minimum = Bytes.parseBytes(properties.getProperty(i + ".minimum", "10GB"));
        multiplier = Double.parseDouble(properties.getProperty(i + ".multiplier", "1"));
        if (minimum < 0 || !Double.isFinite(multiplier) || multiplier < 0) {
            throw new IllegalArgumentException("usablefreespace requires a nonnegative minimum and finite nonnegative multiplier");
        }
    }

    @Override
    public void process(ScoreChart chart, User user, InetAddress peer, char direction,
                        InodeHandleInterface inode, RemoteSlave sourceSlave) {
        Map<ScoreChart.SlaveScore, Long> headroom = new LinkedHashMap<>();
        chart.getSlaveScores().removeIf(score -> {
            RemoteSlave slave = score.getRSlave();
            try {
                long usable = slave.getSlaveStatusAvailable().getDiskSpaceUsable();
                if (slave.isUploadBlockedByCapacity() || usable <= 0) return true;
                headroom.put(score, usable);
                return false;
            } catch (SlaveUnavailableException e) {
                return true;
            }
        });
        // Apply after section restrictions: never fall back to a slave outside this chart.
        boolean hasComfortableDestination = headroom.values().stream().anyMatch(bytes -> bytes >= minimum);
        chart.getSlaveScores().removeIf(score -> hasComfortableDestination && headroom.get(score) < minimum);
        for (ScoreChart.SlaveScore score : chart.getSlaveScores()) {
            long bonus = (long) Math.min(Long.MAX_VALUE / 4.0, headroom.get(score) * multiplier);
            score.addScore(bonus);
        }
    }
}
