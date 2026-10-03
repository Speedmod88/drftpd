# Release catalogue and idle CRC audit

This change extends the combined All_Fixes build. It does not replace, disable or
move existing upload/download checksum work, CHECKSUM/RESCAN commands, Dupe2
scoring, keep buckets, or automatic cleanup rules.

## Persistent data

* runtime/master/userdata/index: existing Lucene search database, now also
  storing normalized release keys, parsing-schema fingerprints and release tags.
  Existing zipscript present/missing/percent fields remain inventory summaries,
  not proof that every physical copy has recently passed a CRC scan.
* runtime/master/userdata/integrity-audit: separate embedded Lucene database.
  One record per slave/path stores size, modification time, expected CRC,
  measured CRC, reference source, last audit time and mismatch status. Cursor
  records preserve progress separately for each slave and catalogue backfill.
* VFS plugin metadata records known-bad slave copies. DUPE2/FIND/AutoFreeSpace
  cannot treat a release with a known bad current copy as a completed keeper or
  automatic duplicate deletion candidate.

These paths are relative to the master's working directory and are outside the
normal FTP site tree. Back up the audit database with VFS/userdata; unlike the
derived search index, it contains historical baselines that an index rebuild
cannot reconstruct. Stop the master for a consistent filesystem backup.
Updates are coalesced and committed every 30 seconds, at 512 pending records, on
a new mismatch, and at orderly shutdown. After abrupt termination, up to the
last uncommitted batch may be repeated; committed work is recovered.
The currently partially-read file is restarted after a process restart.

No external database service, SRR generation or SRR upload is introduced. Existing
manual srrDB SFV/NFO recovery remains unchanged. Downloaded reference CRCs are not
treated as evidence that physical files were read or are healthy.

## Enable and tune

Copy config/plugins/integrity.conf.dist to config/plugins/integrity.conf on the
master, choose the scope, then use SITE RELOAD or !reload:

    audit.enabled=true
    audit.slaves=Evo EvoI
    audit.paths=/ARCHIVES,/TV-HD-FRENCH
    audit.exclude.regex=(?i)^/PRE(?:/|$)
    audit.idle.seconds=300
    audit.interval.days=90
    audit.minimum.age.minutes=60
    audit.steps.per.tick=8
    audit.scan.batch=32
    catalogue.backfill.enabled=true
    catalogue.backfill.batch=32

Defaults leave physical CRC auditing disabled. Empty audit.slaves means all
capable slaves; default audit.paths=/ covers the VFS except excluded paths.
Add your actual predir paths to the exclusion regex if they are not /PRE.
Catalogue backfill is enabled by default: at most 32 directories every
30 seconds, paused while FIFO3 contains at least 5,000 events. It uses indexed
paths and VFS metadata, never physical slave checksums. Set
catalogue.backfill.enabled=false to disable this optional backfill.

Old index records and changed parsing schemas use legacy name-query fallback
until reindexed. DUPE2 still validates live metadata and applies configured
scoring/keep rules before cleanup decisions; stale index entries are not
authorization to delete files. S01E01, E01 and E001 remain distinct according to
existing marker rules. This is name-based variant matching, not content
identity detection; game edition/platform/version rules still require suitable
marker configuration.

## Audit behavior and limits

The registered integrity.dispatch timer only dispatches to a dedicated,
low-priority master worker. It does not checksum on shared timers or event queues.
Each updated slave uses a separate single-worker audit executor, not its normal
checksum or transfer pool.

Only online, non-remerging slaves with no transfers and a completed idle grace
period are eligible. The slave also checks pending/active checksum, filesystem
and remerge work. Each command reads at most 4 MiB, checking activity between
64 KiB reads and closing its file descriptor between commands. At defaults the
global budget is at most eight steps per one-second tick, shared between slaves.
Actual progress depends on disk speed, other activity and eligibility.
An already-blocked OS read cannot be preempted; Java thread priority is not an
operating-system disk bandwidth guarantee.

Starting transfer activity invalidates partial CRC progress. Changed file size,
mtime, file identity, VFS transfer state, expected CRC or slave connection also
prevents obsolete results from being accepted. Busy slaves are deferred, never
taken offline by an audit response timeout.

The scheduler visits indexed, nonempty, non-uploading files old enough for the
configured minimum age and excludes recorded failed transfers. An unchanged
file becomes due again after audit.interval.days even if its slave remains
online for years. The interval is eligibility, not a completion deadline:
continuously busy slaves may never have enough idle time to finish all audits.
Every known slave copy is checked separately. Multiple physical roots holding
the same path on one slave are ambiguous and skipped with a warning.

Expected CRC comes from existing SFV metadata when available, otherwise a
previous audit reference or cached VFS CRC. With no expected CRC, the first scan
establishes a **baseline**, not proof of correctness. Later changes against that
baseline can be detected. CRC32 cannot repair files or prove cryptographic
integrity. SFV/DIZ metadata that has never been loaded remains unknown; this job
does not fetch and parse unknown SFVs or DIZ files from slaves.

A good copy does not clear a bad-copy record on another slave. Shared VFS CRC
is filled only when all known copies have matching successful audit records for
the same file fingerprint. Legacy VFS uses CRC zero as unknown; legitimate zero
CRCs are still preserved correctly in the audit database.

## Alerts

New or changed mismatches log an ERROR from
org.drftpd.zipscript.master.audit.IntegrityAudit and publish a SysopEvent:

    CRC MISMATCH slave=Evo path=/ARCHIVES/Release/file.rar expected=12345678 actual=87654321 reference=sfv (file kept)

Use existing sysop routing in irc.announce.conf for your sysop channel.
Repeated identical mismatches do not repeatedly announce. A good recheck clears
that copy's failure; a later new mismatch announces again. Existing SiteBot
delivery rules apply; this does not add a durable IRC delivery receipt/outbox.
No files are deleted, nuked, overwritten or repaired by background auditing.

## Deploy this change

From a complete combined build, replace these files while the relevant process
is stopped (version suffix remains 4.0.12-SNAPSHOT):

* Master: build/drftpd-master-4.0.12-SNAPSHOT.jar
* Master: build/drftpd-autofreespace-master-4.0.12-SNAPSHOT.jar
* Master: build/drftpd-zipscript-master-4.0.12-SNAPSHOT.jar
* Master: lib/drftpd-slave-4.0.12-SNAPSHOT.jar
* Each slave to audit: build/drftpd-slave-4.0.12-SNAPSHOT.jar
* Master: new config/plugins/integrity.conf.dist, then your enabled .conf.

Keep existing active configuration files. Older slaves continue normal operation
but are skipped by idle auditing; new slaves still support older masters.
The capability probe uses the existing maxpath command/response; only
supporting slaves receive idlecrc. Normal checksum messages are unchanged.
To stop physical auditing, set audit.enabled=false and reload. This does not
disable normal transfer checksums or remove saved audit records.
