# Legacy slave retry compatibility

The master probes zero-byte deletion support through the existing MAXPATH
command before sending DELETEZERO. Legacy slaves return a normal path limit.
An unsupported retry fails without sending DELETEZERO, deleting a nonempty
file, or removing the pending delete. Normal post-remerge queue processing
is unchanged. No configuration changes are required.

## Deployment

Stop each process before replacing its JARs. Build from All_Fixes, not only
this feature branch. Deploy the matching master build JAR and slave JAR to
master/lib, plus the matching common JAR in master/lib. On updated slaves,
deploy the slave build JAR and common JAR in slave/lib together. Keep other
required plugin libraries; do not replace the entire lib directory blindly.

A NoSuchMethodError for DiskStatus(long,long,boolean,String,Long) means the
slave loaded an older local drftpd-common JAR. It is not a remerge filesystem
failure. Check for duplicate older common JARs on the runtime classpath.
Legacy slaves may remain deployed with their existing matched libraries.
