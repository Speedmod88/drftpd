# Links, DUPE2, SiteBot and passive listener fixes

## Link updates

Uploads, SFV completion and VFS create/refresh/last-modified/delete events enqueue
the affected directory for a background metadata-only reconciliation. Requests are
coalesced by path. One daemon processes at most 32 paths per batch, every 2 seconds,
with at most 2048 pending paths and 15 attempts when verification is still unknown.
An overflow is logged at most once per minute. Reload does not start extra workers.

Existing SFV files remove no-SFV links. Verified completion removes incomplete
links; unknown CRC/SFV metadata preserves the marker. Late MKD/delete events check
current state before creating a marker. Only matching symlinks are removed, never
the release or a different target with the same generated name.

This is event-driven, not an unlimited scan of every historic link. Use SITE
FIXLINKS in a specific affected release for old markers without new events.
The existing manual FIXLINKS verification can still request CRC reads.

## DUPE2

Completion uses existing VFS SFV/DIZ metadata and cached checksums only. It does
not fetch SFVs, DIZ data or CRCs from slaves. This also applies to AutoFreeSpace
and FIND duplicate candidate eligibility using the shared Dupe2 utility.

Reports distinguish completed, incomplete and unknown. Unknown releases are
not automatic deletion candidates or replacement keepers. A zero cached checksum
is treated as unknown because the existing VFS uses zero as its missing-CRC value.
Explicit checksum/rescan commands are unchanged.

## SiteBot

Announcer construction and channel-writer/routing initialization use the same
monitor as connect/reload. Routing cannot be installed from an empty writer
snapshot while channel setup skips its refresh. Readiness requires channel
writers, not just a connected IRC socket. The existing OPER delay is preserved.
Updated route maps are safely published to event workers.
Concurrent startup paths notify announcers only once per connection.

## Passive listeners

The slave setting passive.listener.timeout.seconds defaults to 180 (30..3600).
Only an unused PASV reservation is expired. RECEIVE/SEND starting cancels expiry;
active uploads/downloads are not terminated by this timer.
Setup failures and disk-full rejections retain the existing finally-based cleanup.
No wire protocol or serialized response fields changed.

An exhausted range can still mean every port is legitimately busy or occupied by
another process. Update the common JAR as well as the slave JAR; the old common
library reports RuntimeException: PortRange exhausted instead of the current
BindException containing the configured range.

## srrDB

Enter the full physical VFS release directory, with or without its final slash.
For example:

    /_INCOMPLETED/TV-SD-FRENCH/Amphibia.S03E18.FiNAL.FRENCH.WEB.H264-C0MPL3T3D/

Only Amphibia.S03E18.FiNAL.FRENCH.WEB.H264-C0MPL3T3D is sent as the API release
name. Approved metadata goes to that same full VFS directory, not the FTP root
or a similarly named release elsewhere. Repeated/trailing slashes are normalized
in requests and persisted review entries. Manual approval and no-overwrite checks
remain mandatory. Symlink destinations remain disallowed.

## Deployment from the combined build

Stop each affected process before replacing its files, then restart.

Master build JARs:

- drftpd-autofreespace-master-4.0.12-SNAPSHOT.jar
- drftpd-links-master-4.0.12-SNAPSHOT.jar
- drftpd-zipscript-master-4.0.12-SNAPSHOT.jar
- drftpd-sitebot-master-4.0.12-SNAPSHOT.jar
- drftpd-webadmin-master-4.0.12-SNAPSHOT.jar

On each slave, for passive cleanup:

- build/drftpd-slave-4.0.12-SNAPSHOT.jar
- lib/drftpd-common-4.0.12-SNAPSHOT.jar

Do not add Mockito/Byte Buddy test libraries to the running installation.
The full All_Fixes build also refreshes the other JARs without removing prior fixes.
