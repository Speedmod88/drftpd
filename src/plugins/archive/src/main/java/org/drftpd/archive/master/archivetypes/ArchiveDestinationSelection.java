package org.drftpd.archive.master.archivetypes;

import org.drftpd.master.exceptions.SlaveUnavailableException;
import org.drftpd.master.slavemanagement.RemoteSlave;
import java.util.*;

final class ArchiveDestinationSelection {
    private ArchiveDestinationSelection() { }

    static Set<RemoteSlave> mostFree(Collection<RemoteSlave> configured,
                                     Set<RemoteSlave> excluded, int count) {
        List<Map.Entry<RemoteSlave, Long>> available = new ArrayList<>();
        for (RemoteSlave slave : configured) {
            if (excluded.contains(slave) || slave.isUploadBlockedByCapacity()) continue;
            try {
                long free = slave.getSlaveStatusAvailable().getDiskSpaceAvailable();
                if (free > 0) available.add(Map.entry(slave, free));
            } catch (SlaveUnavailableException ignored) {
                // Offline destinations remain configured for a later cycle.
            }
        }
        available.sort(Comparator.<Map.Entry<RemoteSlave, Long>, Long>comparing(Map.Entry::getValue)
                .reversed().thenComparing(entry -> entry.getKey().getName()));
        Set<RemoteSlave> result = new LinkedHashSet<>();
        for (var entry : available) {
            if (result.size() >= count) break;
            result.add(entry.getKey());
        }
        return result;
    }
}
