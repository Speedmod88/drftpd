# Missing SFV display

WebAdmin DUPE2 rows and verification output show MISSING SFV instead of UNKNOWN
when the VFS inventory confirms no SFV in a release (or in a numbered disc).
ZIP and file_id.diz releases are not mislabeled as missing SFV. An unreadable
directory or an existing but untrusted SFV stays UNKNOWN.

This is a display reason, not a new trusted completion state. Missing-SFV rows
remain part of UNKNOWN selection and verification and are not automatic deletion
candidates. No slave reads, new checksums, recovery downloads or configuration
changes are introduced. Recover an appropriate SFV and verify again.

Only the WebAdmin master JAR changes. Stop the master before replacing it, then
restart and run a new scan/verification. Previously saved executions retain their
original labels. No slave update is required.
