# Missing srrDB metadata through normal FTPS

The reported Connect timed out failures happened at SrrdbLibrary.install's
master-to-slave socket connect, after the HTTPS download had succeeded and its
size/CRC had been verified. The previous uploader also reserved a VFS file before
the socket connected, which could leave a zero-byte reservation after a timeout.

Missing SFV/NFO imports now log in to the local master through explicit FTPS
(AUTH TLS), using a dedicated account configured locally. The client issues PBSZ 0,
PROT P, TYPE I, CWD, PRET STOR and normal passive STOR. DrFTPD owns slave selection,
capacity checks, permissions, physical/VFS file creation and upload post-hooks.
The recovery library no longer creates a VFS file or manually emits STOR events.
After STOR completes, SIZE and fresh XCRC verify the uploaded metadata. Filenames
containing spaces use a bounded FTP RETR verification because the existing XCRC
parser tokenizes on spaces. No RAR payload is uploaded or repaired.

## Connection requirements

Control login connects to a local IPv4 address of this master by default.
It uses master.ip and master.port, with loopback when the master binds all
interfaces. Optional webadmin.conf settings override this local endpoint:

```properties
srrdb.ftp.host=
srrdb.ftp.port=0
srrdb.ftp.user=
srrdb.ftp.pasword=
```

Blank/zero retains automatic local selection. A manually configured host may be
any hostname resolving to IPv4, or an IPv4 literal, with a custom port. Only that
configured host is resolved for the control connection; no secondary server is
selected automatically. Configure only your trusted FTP endpoint: it receives
the dedicated recovery credentials. WebAdmin passwords are never used for FTP.
There is no deployment-specific hostname or port in the code. The client uses
the master's existing SSL context and TLS trust policy. PASV and PROT P remain
enabled; changing the control host does not relay file data through the master.

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

WebAdmin passwords are not retained for recovery or used for FTP login. Configure
srrdb.ftp.user and srrdb.ftp.pasword (intentional spelling) for a dedicated account.
Both must be nonempty; otherwise imports fail before connecting. Credentials are
stored in the local plaintext configuration, not source, recovery JSON or job
history. Protect webadmin.conf with owner-only access and do not commit secrets.
Reload WebAdmin/restart after changes. FTP refusal replies redact the password.
Normal login slots and IP/CWD/upload/XCRC permissions apply to the dedicated user;
that account does not need siteop privileges. WebAdmin approvals still require siteop.
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
Existing webadmin.conf files must add the dedicated user/password before imports.

Automated tests cover the FTPS command order, distributed passive settings,
dedicated credentials, endpoint restrictions, timeout diagnostics, CRC failure
without deletion, and verification of filenames containing spaces. Real EvoI
network reachability must be checked on the running installation.
