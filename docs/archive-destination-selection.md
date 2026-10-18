# Archive destination selection

For MoveReleaseOffSlavesToMostFreeSlaves and MoveReleaseToMostFreeSlaves,
slavename.N is the allowed destination pool for that rule. Availability and
capacity are checked within that pool before ranking by free bytes. Equal
free-space values retain both slaves, with slave name as a stable tie-break.

With Evo and EvoI configured and numofslaves=1, the available destination with
the most free space is selected. An unrelated slave cannot displace it.
Offline, FULL, zero-space and explicitly excluded slaves are not selected.
Existing job-manager transfer rules still apply.

Source names in offofslave.N are trimmed. Unknown names and source/destination
overlap now produce warnings. A rule with no valid source still fails loading.

Insufficient destinations defer the rule to a later cycle with a warning.
All file jobs for one release use the same destination snapshot.

No configuration syntax/default changes. Update only the archive master
plugin JAR for this change; no slave update is needed.
