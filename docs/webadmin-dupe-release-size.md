# DUPE2 Release Size and Confirmation

The WebAdmin DUPE2 table includes a Size column, using the release directory's
existing VFS byte total, including its subdirectories. This is the current
recorded size, not an estimate of the final size of an incomplete release or a
sum of redundant physical copies. No slave scans or checksum requests are added.
Unreadable or invalid sizes display Unknown; an empty release displays 0B.

New saved execution results retain sizeBytes alongside completion information.
Older history without that field displays Unknown until a new scan is run.

The confirmation dialog is up to 1100 pixels wide, constrained to the viewport.
Long paths wrap and long selections scroll. Confirmation, permission checks and
recursive deletion behavior are unchanged.

Deploy only drftpd-webadmin-master-4.0.12-SNAPSHOT.jar from the combined All_Fixes
build while the master is stopped, then restart. No configuration, theme or slave
updates are required.

Verification: 77 targeted WebAdmin tests passed; browser checks cover sizes,
legacy history fallback, group headings, selection and deletion confirmation at
1440x1000 and 390x844, without executing a deletion.
