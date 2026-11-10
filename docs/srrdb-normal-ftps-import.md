# Missing srrDB metadata through normal FTPS

The reported Connect timed out failures happened at SrrdbLibrary.install's
master-to-slave socket connect, after the HTTPS download had succeeded and its
size/CRC had been verified. The previous uploader also reserved a VFS file before
the socket connected, which could leave a zero-byte reservation after a timeout.

Missing SFV/NFO imports now log in to the local master through explicit FTPS
(AUTH TLS), using the logged-in WebAdmin account. The client issues PBSZ 0,
PROT P, TYPE I, CWD, PRET STOR and normal passive STOR. DrFTPD owns slave selection,
capacity checks, permissions, physical/VFS file creation and upload post-hooks.
The recovery library no longer creates a VFS file or manually emits STOR events.
After STOR completes, SIZE and fresh XCRC verify the uploaded metadata. Filenames
containing spaces use a bounded FTP RETR verification because the existing XCRC
parser tokenizes on spaces. No RAR payload is uploaded or repaired.

## Connection requirements

Control login connects to a local IPv4 address of this master only. By default,
it uses master.ip and master.port, with loopback when the master binds all
interfaces. Optional webadmin.conf settings override this local endpoint:

```properties
srrdb.ftp.host=
srrdb.ftp.port=0
```

Blank/zero retains automatic selection. External FTP control hosts are refused
to avoid sending WebAdmin login credentials to another server. The client uses
the master's existing SSL context and TLS trust policy.

The account needs an available FTP login slot, permission to log in from the
master's local source address, and normal CWD/upload/download/XCRC permissions.
No permission, FULL state or account restriction is bypassed.

The FTP client honors the slave IP in PASV replies instead of substituting the
master control address, disables EPSV for IPv4 and permits a data peer different
from the master (normal distributed FTP). Data remains protected by PROT P.
The master/container must still reach the selected slave's advertised passive
address and port. Changing the upload workflow cannot repair firewall, NAT
loopback or port-forwarding failures. The actual passive endpoint is logged and
included in stage-specific failures.

DrFTPD can send multiline PASV replies: a `227- Using <slave>` comment followed
by the terminating `227 Entering Passive Mode (...)` address line. The recovery
client selects that terminating line before invoking Commons Net's passive
address parser; the comment is not an endpoint. Single-line replies remain
supported, and a malformed terminating line is still rejected.

## Credentials and restart

Successful WebAdmin login keeps a separate in-memory credential copy, associated
with the session. It is never serialized in session responses, recovery JSON,
job history or logs. Stored character arrays are cleared when the session logs
out, expires, is replaced, is rejected by authorization, or the server stops.
Queued work without an active WebAdmin login fails with a sign-in message.
Restarted in-progress uploads become failed and require manual Retry.

Old failed attempts may already have left zero-byte VFS reservations. They are
not automatically deleted or overwritten: inspect them through FTP, remove only
confirmed unwanted empty entries, then Retry. Existing nonempty files are kept.
Approved mismatch replacement retains its existing guarded backup/CRC workflow;
this change routes missing-file uploads through FTP.

## Deployment and verification

Update runtime/master/build/drftpd-webadmin-master-4.0.12-SNAPSHOT.jar from the
combined build, restart the master, and sign in again. The existing master lib
commons-net-3.10.0.jar is required; no new slave protocol or slave update is needed.
The distributed webadmin.conf.dist documents the two optional endpoint settings.
Existing webadmin.conf files need no changes when automatic local selection works.

Automated tests cover the FTPS command order, distributed passive settings,
credential expiry/logout, endpoint restrictions, timeout diagnostics, CRC failure
without deletion, and verification of filenames containing spaces. Real EvoI
network reachability must be checked on the running installation.
