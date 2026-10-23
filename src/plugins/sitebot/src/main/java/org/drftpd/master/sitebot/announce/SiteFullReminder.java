package org.drftpd.master.sitebot.announce;

import org.drftpd.master.slavemanagement.RemoteSlave;
import java.util.Collection;
import java.util.concurrent.TimeUnit;

/** One reminder stream per bot; offline/unknown slaves never count as full. */
final class SiteFullReminder {
    static final long INTERVAL = TimeUnit.HOURS.toNanos(6);
    private boolean announced;
    private long lastAnnouncement;

    synchronized void check(Collection<RemoteSlave> slaves, long now, Runnable announce) {
        boolean full = !slaves.isEmpty() && slaves.stream()
                .allMatch(slave -> slave.isAvailable() && slave.isUploadBlockedByCapacity());
        if (!full) {
            announced = false;
            return;
        }
        if (!announced || now - lastAnnouncement >= INTERVAL) {
            announce.run();
            lastAnnouncement = now;
            announced = true;
        }
    }
}
