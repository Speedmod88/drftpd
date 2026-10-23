# Capacity recovery and idle audit fix

* Root lookup enumerates each configured root once. Multiple real copies still
  block the idle CRC audit; missing files still raise FileNotFoundException.
  Upload/download CRC handling is unchanged.
* The generic FTP unavailable response is now:
  450 No transfer-slave(s) available or Site are FULL
  This remains a generic response (including download failures), not proof of fullness.
* SiteBot checks cached slave status every 30 seconds while connected and ready.
  If every configured slave is available and reports FULL, it sends:
  SITE FULL: All configured slaves report FULL (minfreespace). New uploads are blocked. Reminder every 6 hours while full.
  Output follows explicit sysop channel routing in irc.announce.conf (no fallback
  to a public default channel). Configure sysop.destination if not already set.
  It repeats every six hours, rearms after recovery, and stops with the announcer.
  Offline slaves, old slaves without capacity status, and an empty slave list
  cannot trigger this warning. Bot reload/restart resets reminder state.
* Optional slave config/diskselection.conf setting, per minfreespace rule:

  1.recovery.margin=50GB

  Default is 0B for backward compatibility. A root first blocks at free <= minimum;
  once blocked, it recovers only when free > minimum + margin. Restart the slave
  to apply settings; the in-memory hysteresis state resets at restart.
  While blocked, reported usable free space stays zero for that root.
  Existing scoring versus hard-exclusion multiplier semantics are preserved.

Deploy these combined-build files:

* runtime/master/build/drftpd-master-4.0.12-SNAPSHOT.jar
* runtime/master/build/drftpd-sitebot-master-4.0.12-SNAPSHOT.jar
* runtime/master/lib/drftpd-slave-4.0.12-SNAPSHOT.jar
* runtime/slave/build/drftpd-slave-4.0.12-SNAPSHOT.jar (on each slave)

Stop the corresponding process before replacing its JARs, then restart it.
No protocol fields were added; mixed versions retain their old behavior.
No existing runtime configuration is replaced. The only new config key is
N.recovery.margin in the slave diskselection.conf; no master.conf key is needed.
