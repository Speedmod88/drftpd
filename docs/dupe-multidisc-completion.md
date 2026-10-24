# Dupe2 multi-disc completion

UNKNOWN verification already saves file CRCs and SFV/DIZ metadata in the VFS.
The failure was that release completion only checked files at the top level:
DISK1/DISK2/DISK3 could each be COMPLETE while their parent stayed UNKNOWN.

When no local SFV or ZIP completion reference exists, completion now checks
numbered CD, DISC, DISK and DVD children, using cached metadata only. Every disc
must be COMPLETE; unknown or incomplete children prevent a complete result.
Numbers must start at 1 without gaps or duplicate numbers. Arbitrary directory
names, symlinks, extra unverified root payloads and excessive nesting remain
UNKNOWN. Only NFO/DIZ/TXT sidecar files are permitted at this container level.
As with SFV verification generally, files or final discs absent from every
available reference cannot be inferred.

The parent is recalculated, not given a permanent COMPLETE flag, so later child
changes still affect eligibility. This applies to shared Dupe2/FIND/AutoFreeSpace
completion checks. Ordinary lookups never request remote CRCs.
Explicit WebAdmin verification refreshes and reports the final parent status
after scanning its children. No CRC transfer behavior or configuration changed.

Deploy the combined master-side JARs:
* runtime/master/build/drftpd-zipscript-master-4.0.12-SNAPSHOT.jar
* runtime/master/build/drftpd-webadmin-master-4.0.12-SNAPSHOT.jar

Stop the master before replacing its JARs, then restart.
Already verified discs should not need another CRC scan: run a new Dupe2 lookup.
Saved execution history remains an immutable snapshot and is not rewritten.
