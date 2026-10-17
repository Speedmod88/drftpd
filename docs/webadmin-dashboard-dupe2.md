# WebAdmin dashboard and duplicate review

The authenticated home page is now a dashboard. It shows system/JVM CPU, system
RAM, JVM heap, logical CPU count, JVM threads, uptime, usable slave capacity and
1/5/15-minute load averages. Unsupported metrics are displayed as unavailable.
The graph shows samples collected while the dashboard is open, not historical
monitoring data. Metrics refresh every 5 seconds; SITE STATUS ALL and SITE SWHO
output refreshes every 30 seconds without overlapping requests.

## Dupe2

The dedicated tab scans indexed directories recursively below the supplied VFS
directory, without a result-count cap. Scanning runs on the existing bounded web
command executor. Results are paginated at 100 rows. They are the same completed
losers selected by shared Dupe2 scoring/keep rules across sections, not all variants.
Unknown/incomplete releases and keepers are not offered. No new CRC reads are
introduced. As with FIND, results depend on the current index and cached metadata.

Nothing is deleted during scanning. Select at most 100 results per batch and
confirm the full path list to delete. The server binds selections to a completed
scan owned by the logged-in user and rechecks eligibility before each SITE WIPE -r.
Normal command permissions/hooks and offline-slave deletion queues still apply.
One Dupe2 job per user may run at once. The batch stops on command failure; completed
wipes are not rolled back. Always rescan after a wipe, including a partial failure.
Concurrent administrators can change releases between checks; this is not a
transactional filesystem snapshot. Do not run competing cleanup jobs on the same
releases during review.

## Slave files

No remote file-management protocol is added to slaves. To view logs/edit configs,
mount the desired folders into the master host/container and explicitly configure
their roots in webadmin.conf. Old slaves remain compatible. Read-only logs mounts
are supported. Config mounts need write permission for the master OS user.

Example, using consecutive numbering:

    slave.1.name=EvoI
    slave.1.config.root=/mnt/slave-admin/EvoI/config
    slave.1.logs.root=/mnt/slave-admin/EvoI/logs

Configuration and Logs get server selectors. Only .conf/.conf.dist can be edited,
and only .log files can be tailed. Relative-path and symlink restrictions apply.
Saves check the opened file version and create timestamped .webadmin-backups.
Slave config saves do not reload/restart the slave. Do that separately when needed.

## Update

Update runtime/master/build/drftpd-webadmin-master-4.0.12-SNAPSHOT.jar from the
combined All_Fixes build with the master stopped, then restart the master.
The existing AutoFreeSpace plugin JAR is required for Dupe2. No slave JAR changes.

The distribution template config/plugins/webadmin.conf.dist has new optional
slave-root examples and two new allowed commands. In an existing webadmin.conf,
add these using the next consecutive unused command.allowed numbers:

    command.allowed.12=site status
    command.allowed.13=site swho

Do not overwrite the live configuration with the default disabled/loopback
template. Existing HTTPS binding, siteop-only authentication and 24-hour session
behavior remain unchanged. Active configs are not modified by this update.
