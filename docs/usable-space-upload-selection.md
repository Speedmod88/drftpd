# Reserve-aware upload selection

The opt-in usablefreespace filter scores reported usable bytes above the slave's
diskselection reserves, not its physical free space. The existing master FULL
gate remains authoritative. No additional slave RPC or filesystem scan is used.

Place it after section routing/removal rules and before cycle, with consecutive
filter numbers. Example:

```properties
6.filter=usablefreespace
6.minimum=10GB
6.multiplier=1
7.filter=cycle
```

If an allowed slave has at least minimum usable bytes, allowed slaves below that
minimum are skipped. If all allowed slaves are below it, positive-space non-FULL
slaves remain candidates; the filter does not override section restrictions or
invent a SITE FULL condition. FULL, zero-space and unavailable slaves are excluded.

Each remaining slave receives usable bytes multiplied by multiplier as a score
bonus. The default multiplier of 1 strongly favors the most usable space and may
dominate existing bandwidth penalties; this is not round-robin balancing. A
multiplier of 0 enables the near-reserve avoidance alone while keeping existing
bandwidth scores. Lower positive values reduce disk space's scoring influence.

Reported space is a snapshot, not a reservation for the entire incoming file.
Uploads already in progress are not moved. This cannot guarantee capacity for an
unknown-size STOR or fix unrelated FXP/TLS/queued-cleanup errors returning 426/450.

Old slaves remain protocol-compatible; without usable-space reports, their raw
physical free space is used. Their unreported reserves cannot be inferred.

Deploy the updated master JAR before enabling this filter in slaveselection-up.conf;
an older master cannot load an unknown filter name. No slave or library update is
required. The distribution config adds comments only. Keep existing per-slave
minimums and recovery margins unchanged. Reload after deploying the active config.
