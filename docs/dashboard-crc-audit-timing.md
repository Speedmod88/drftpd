# Background CRC audit timing

The WebAdmin Timers page displays the existing integrity audit's configured idle
grace, minimum file age and per-file recheck interval. Its live worker snapshot
also lists each slave's waiting reason, next eligibility time, current file,
last completed CRC check and last error. Completion times refer to this master
session. The next eligibility time is conditional on the slave staying idle and
online; it is not a guaranteed scan start or completion time.

The audit's existing dispatcher runs every second after its initial 30-second
delay. An exhausted candidate pass waits one hour before searching again. Neither
value is the configured per-file recheck interval (audit.interval.days).

The Timers page refreshes every five seconds while visible. Reading the status
does not issue CRC commands, query the audit database or schedule additional
audit work. The snapshots are published by the existing audit worker. Existing
timer registrations remain visible below the CRC section.

No configuration changes or slave updates are required. Update the combined
build's drftpd-webadmin-master and drftpd-zipscript-master JARs on the master,
then restart the master.
