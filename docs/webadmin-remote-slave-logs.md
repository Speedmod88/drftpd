# Remote slave logs without mounts

WebAdmin Logs lists each configured slave as `Name (remote)`. Requests use the
existing authenticated master/slave connection: no new port, SSH or mounts.
Siteop authentication is still required. Mounted log/config scopes still work.

In each participating slave's config/slave.conf add:

    remote.logs.enabled=true
    remote.logs.root=logs

The root is relative to the slave working directory, or an absolute local path.
It and its ancestors must not be symlinks. Only simple .log filenames directly
under that root are accepted; no symlinks or other file types. There is no write,
delete, config editing, compressed-log or recursive browsing API. Trust the master:
logs may contain sensitive data.

Reads are capped at 256 KiB and 2000 lines; listing returns at most 100 files.
A separate single worker and four-request queue isolate log reads from transfer,
control and checksum workers. WebAdmin limits remote requests to two concurrently
and stops Follow on errors. A 10-second timeout per exchange does not disconnect
the slave. Slow storage can still hold the log worker until the OS read returns.
No historical copy is stored on the master.

An existing MAXPATH probe checks support before sending the new command. Old slaves
return their normal path length and display an upgrade notice without being sent
an unsupported command. Disabled/offline slaves display errors. New slaves work
with older masters, which never request remote logs.

Deployment: stop the master; replace the combined build's WebAdmin master JAR and
slave JAR in runtime/master/lib; restart. Stop each participating slave, replace
its runtime/slave/build slave JAR, enable the settings above, then restart. Other
slaves can be updated later. No master configuration change is required.
