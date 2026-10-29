package org.drftpd.zipscript.master.audit;

import java.util.List;

/** Immutable worker snapshots; dashboard reads never inspect files or contact slaves. */
public record IntegritySchedule(boolean loaded, boolean enabled, long idleMillis, long intervalMillis,
                                long minimumAgeMillis, long nextCheckAt, long lastCycleAt,
                                long lastCompletedAt, String error, List<Slave> slaves) {
    public IntegritySchedule { slaves = List.copyOf(slaves); }

    public static IntegritySchedule unavailable() {
        return new IntegritySchedule(false, false, 0, 0, 0, 0, 0, 0, "", List.of());
    }

    public record Slave(String name, String state, long nextEligibleAt, long lastCompletedAt,
                        String path, String error) { }
}
