# Persistent compressed NFO display

## Storage and size

The master keeps NFO text in a separate Lucene-backed store under
userdata/nfo-display. Each record's text is individually gzip-compressed; only
paths and identity metadata are indexed. No new database dependency is required,
and the store is outside the FTP VFS. It is not a second copy of release payloads.

Space depends on the number and compressibility of NFOs plus index overhead.
For example, 100,000 NFOs averaging 2 KiB compressed would contain about 195 MiB
of compressed text, plus database metadata/index overhead. This is illustrative,
not a measurement or a guaranteed compression ratio for this site.

The existing 64 KiB per-source-file bound remains. The original decoded text is
stored so changing display limits does not require downloading the NFO again.
Decompression is bounded, and existing control-code/line-length sanitization is
applied before text is added to FTP replies. This feature does not create SRRs.

## CWD behavior

The existing download permissions, encryption restrictions, hidden-file checks,
and verified-COMPLETE release gate remain in force. Incomplete/UNKNOWN releases
never display NFO text. The completion gate is checked again after the read.

A RAM hit is immediate. On a miss, a private low-priority worker looks up the
persistent record, validating the path, creation time, size, modification time
and cached CRC against current VFS metadata. It otherwise fetches one bounded
NFO through the existing capability-probed slave command. CWD waits at most
cwd.wait.millis (default 500, range 0..2000); a slow/offline slave or long queue
may still mean no NFO on that visit. The background result can serve later visits.

There is no database or network read on the FTP command thread. At most 32 NFO
requests are pending/queued, misses for the same identity are coalesced, and one
private worker serves them. Setting cwd.wait.millis=0 restores asynchronous CWD.
Successful STOR events prefetch NFOs, including normal srrDB FTPS imports.

This does not fetch SFV/DIZ metadata or rescan payload CRCs during CWD. Old releases
whose completion is UNKNOWN still need the existing verification/integrity tools.
Having a cached NFO is not proof that a release is complete.

## Background prefetch

The registered timer nfo-display.maintenance dispatches bounded worker maintenance;
it does not scan the entire physical filesystem. Existing NFO paths are discovered
using the main index's .nfo suffix query and exclusive fullPath cursor. Defaults
are up to 8 candidates every 2 seconds. Background slave reads skip offline,
remerging, queued-operation and actively transferring slaves. Index discovery
pauses when FIFO3 has at least 5000 events. Foreground queue work takes precedence
between background candidates. Slave requests share a ten-second per-NFO budget.

After an index pass completes, discovery waits an hour before the next pass;
uploads and CWD misses do not have to wait for that pass. The cursor is stored,
RAM use is bounded, and database updates are committed in batches about every
30 seconds and on clean shutdown. A crash may lose recent cache work, not source
NFO files. Database open/write failure falls back to memory caching with retries.

## Deletion and invalidation

Wipe/delete events are subscribed on the actual slowest VFS event bus. Deleting
an NFO or a release directory invalidates matching RAM entries and schedules
exact-path/descendant deletion from the persistent store. Directory boundary
matching protects similarly named adjacent releases. Rename invalidates both old
and destination paths; refresh invalidates changed NFOs. In-flight reads cannot
publish old text after an observed invalidation. Event subscribers perform no
database I/O, and the worker drains invalidations before database reads.

Invalidations are bounded and coalesced by parent. Overflow safely clears the
cache store for rebuild rather than losing a deletion. A bounded metadata-only
sweep also removes stale records when events were missed while the master was
offline. No payload data, CRC calculation or source-file deletion is involved.
Cleanup is asynchronous and subject to event/worker backlog and commit batching.
Logical deletion removes the text from live queries; Lucene reclaims physical
segment space during normal merges. This is not a secure-erasure guarantee.

## Configuration and deployment

New optional settings in config/plugins/nfo-display.conf (defaults shown):

```properties
store.enabled=true
store.path=userdata/nfo-display
cwd.wait.millis=500
prefetch.enabled=true
prefetch.batch=8
prefetch.interval.seconds=2
```

Existing enabled/max.lines/cache.entries/cache.minutes settings remain. Defaults
apply to existing config files missing the new keys. Keep the store path outside
the FTP site tree. Disable prefetch to fill the store only through uploads/CWD.
With store.enabled=false, display uses the bounded RAM/worker fallback instead.

Update only runtime/master/build/drftpd-zipscript-master-4.0.12-SNAPSHOT.jar from
the combined build and restart the master. nfo-display.conf.dist documents the
new options. The configuration loader uses that .dist file when no active
nfo-display.conf exists; copy it to the active filename only to customize it.
No new slave protocol or core/master JAR update is part of this change. Slaves
must already support the existing NFO read capability; legacy slaves are skipped.

Tests cover restart persistence, identity changes, deletion/rename/subtree
boundaries, in-flight invalidation, CWD deadlines, permissions/completion gates,
idle indexed prefetch, cursor paging, configuration limits and size bounds.
