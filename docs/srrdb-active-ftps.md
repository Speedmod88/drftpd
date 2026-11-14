# Optional active recovery FTPS

Set srrdb.ftp.mode=active in config/plugins/webadmin.conf to use PRET, PORT and
STOR with PROT P. The chosen slave connects back to the recovery client in the
master/container. The recovery client remains the TLS client on that data socket.
Default mode remains passive for existing installations. No automatic mode retry
or downgrade is performed and other FTP/FXP clients are unaffected.

```properties
srrdb.ftp.mode=active
srrdb.ftp.active.address=
srrdb.ftp.active.advertised.host=
srrdb.ftp.active.port.min=0
srrdb.ftp.active.port.max=0
```

The bind address must be a non-loopback IPv4 assigned to the master/container.
Blank uses an assigned non-loopback master.ip when available; otherwise exactly
one eligible local IPv4 must exist. Ambiguous interfaces fail clearly and require
an explicit address, rather than guessing. No hostname or IP is hardcoded.

The control socket also binds this address, even with a loopback master endpoint.
This keeps the peer address compatible with the master's normal private PORT/FXP
checks; no security checks are bypassed. Allow this source IP on the dedicated
FTP account. Firewall/NAT and FTP permissions must allow the slave's connection
back to the advertised address and listener port. This is not a data relay.

The optional advertised host may differ for NAT but must resolve to non-loopback
IPv4. The bind remains local. Ports 0/0 use OS-assigned ports. For a firewall,
configure a positive ordered min/max range and allow/forward it to this container.
No ports or firewall rules are opened by this plugin.

Normal upload selection, quotas, hooks, checksums, SIZE/XCRC verification and
credentials are retained. Existing trace logs include PORT/EPRT, active listener
configuration and preliminary STOR/RETR replies so the selected slave is visible.

Deploy only the updated WebAdmin master JAR with the master stopped, edit the
active WebAdmin config and restart. No slave or library changes. To fall back,
set srrdb.ftp.mode=passive and reload WebAdmin. Preserve dedicated credentials.
