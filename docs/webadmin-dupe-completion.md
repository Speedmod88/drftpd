# Dupe2 completion column

WebAdmin Dupe2 review rows now include a Complete percentage plus completedFiles
and totalFiles counts. The tooltip shows the payload counts. SFV percentages count
finished, nonempty, non-audited-bad files whose cached CRC matches the trusted SFV.
ZIP percentages use the cached valid DIZ expected count, matching the existing
cached ZIP completion rules. Numbered multi-disc containers aggregate payload
counts, not an average of child percentages. Percentages are rounded down.

Unknown expected counts, stale/untrusted SFV metadata, or missing cached CRCs show
Unknown rather than a misleading 100%. This read-only snapshot does not fetch
metadata, read physical files or start CRC scans on slaves. Existing completion
classification and automatic deletion eligibility are unchanged.

Incomplete releases remain in web reviews and their checkboxes remain available
for explicit manual deletion. Wipe confirmation, permission checks, offered-path
validation and active-transfer protection are unchanged. Nothing is auto-selected
or deleted based on the new column.

New saved execution snapshots retain the percentage and counts across logout and
restart. Old snapshots without these fields show Unknown; rerun a scan to populate
the new column. Saved results are snapshots, not live proof a copy is still valid.

Only drftpd-webadmin-master JAR needs deployment. No slave, other plugin, library
or configuration changes. Stop the master, replace the JAR and restart it, then
refresh the browser to load the new assets.
