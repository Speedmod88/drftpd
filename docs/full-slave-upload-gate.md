# Block uploads to reported-full slaves

The master now excludes slaves reporting FULL (minfreespace) before applying
normal upload selection filters, including PRE paths and job/archive destinations.
Scores cannot override this exclusion. Download selection is unchanged.

STOR checks again before creating a VFS file. If a slave became full after PASV,
the master returns 452 and closes the prepared connection through normal cleanup.
It cannot silently change the IP/port already sent to the client. The client must
retry PASV/PORT. Transfers already in progress are not interrupted by this gate.

The gate uses the latest slave disk-status report, not a reservation of free space.
Existing slave disk selection remains authoritative for changes between reports.
Old slaves without minimum-free-space reporting retain their previous behavior.
Once a slave reports space available, it becomes eligible automatically.

No configuration or protocol change is required. Update the master JAR from the
combined All_Fixes build with the master stopped. Existing capacity-capable slave
JARs do not need replacing. A remote 425 Connection refused is not proof of a
space failure: listener cleanup, NAT and firewall behavior must also be checked.
