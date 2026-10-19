# WebAdmin duplicate review

Choose a configured section or enter an absolute VFS path or release name.
The limit counts duplicate groups, not individual releases. Results are grouped
by duplicate key and paginated ten groups at a time.

Every displayed release can be selected manually, including KEEP and UNKNOWN.
Bulk page selection selects only Can delete rows. At most 100 releases can be
submitted per action. Wipe requires explicit confirmation, rechecks access and
active transfers, and invokes the existing SITE WIPE command. Selecting a keeper
can remove the only good copy. Automatic cleanup eligibility is unchanged.

## UNKNOWN verification

Verify selected UNKNOWN submits a bounded background job on a dedicated worker.
It reads missing cached CRCs and refreshes available SFV/DIZ metadata. Active or
unfinished files are skipped. It does not invoke SITE RESCAN, delete files, or
change normal transfer checksum behavior. This is explicit disk-reading work,
not an idle-only scheduled task. Jobs do not survive a master restart.

Missing manifests, unavailable slaves, unfinished files and missing CRCs may
leave a release UNKNOWN. Recover missing manifests separately through srrDB
approval, then verify again. A zero cached CRC remains the VFS unknown sentinel.

The existing integrity.dispatch timer supports periodic idle CRC audits through
integrity.conf. audit.enabled defaults to false. Its catalogue backfill does not
fetch missing SFV/DIZ files. Manual verification does not enable that timer.

## srrDB comparison and logs

Recovery displays local cached SFV/NFO size and CRC alongside srrDB metadata,
comparison status and a source URL. A cached comparison is not a fresh full-file
integrity check. Latest scan comparisons are in memory; recovery approvals retain
their existing persistence. Existing files are never overwritten automatically.

Add the srrdb RollingFile appender and org.drftpd.webadmin.srrdb logger from the
updated log4j2-master.xml to the running master's configuration, preserving local
settings. Recovery logs then go to logs/srrdb.log instead of webadmin.log.

Deploy the combined autofreespace-master and webadmin-master JARs with the master
stopped. No slave JAR or .conf change is required for this feature. The logging
configuration change is in log4j2-master.xml.
