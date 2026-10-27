# Verified srrDB metadata recovery

This change is based on All_Fixes, including the earlier missing-file recovery.
It does not change transfer checksums or Dupe2's metadata-only duplicate lookup.

## Review workflow

- Missing SFV/NFO files use normal slaveselection-up.conf scoring. There is no
  additional preference for a slave that already holds other release files.
- Recursive scans page the index and apply the limit to release directories,
  not to container/Sample directories that happen to appear first.
- Existing small metadata files are freshly checksummed on their registered
  online holders for comparison. Unavailable, active, oversized or disagreeing
  copies remain CRC UNKNOWN and cannot be approved for replacement.
- A mismatch with a verified local baseline offers a separate
  **Replace with backup** confirmation. Accepting a missing file never grants
  permission to overwrite a file that appeared since the scan.
- Queued missing imports run before queued replacements. Running work is not
  interrupted to reorder the queue.
- Review decisions and the original approval baseline are saved in the existing
  recovery JSON state. Interrupted operations require explicit approval to retry.

## SFV replacement

Before changing an SFV, recovery validates the proposed SFV and reads every
referenced local file for a fresh CRC on every registered holder. Missing files,
active transfers, changing VFS metadata, unavailable holders, mismatched copies,
invalid filenames, duplicate SFV entries and omitted local RAR volumes block the
operation. It does not use cached payload CRCs as proof of a match.

The new SFV reference metadata is installed in the VFS only after replacement
has succeeded on all registered metadata holders. This is not a distributed
transaction: a disconnect between holders can leave a partially replaced set.
The operation remains failed and its transaction ID is retained for an explicit
retry. Do not clear a failed entry until its copies have been inspected.

RAR payloads are never repaired, deleted or replaced by this feature. An SRR
does not contain the original archived payload and cannot repair arbitrary RAR
corruption without the required intact source/recovery data.

## Slave safety and compatibility

Replacement requires the updated slave. A capability probe uses the existing
maxpath protocol before sending the optional replacement command. Older slaves
continue to support ordinary missing-file imports but refuse this new operation
without receiving an unknown command.

Each slave requires one unambiguous physical metadata copy and no active
transfers or remerge. It compares the approved size/CRC again, saves the original
under slave userdata/srrdb-backups outside all FTP roots, stages verified content,
then uses an atomic filesystem rename. There is no destructive delete-first
fallback if atomic replacement is unsupported.

Backups and transaction receipts are retained; no automatic purge is performed.
On POSIX, new backup directories/files use 0700/0600 permissions. A leftover
staging file is not overwritten or deleted by a later retry. Inspect it before
retrying a transaction that reports a staging collision.

## Deployment

No .conf changes are required. Install the updated webadmin master JAR and slave
JAR from the combined build. Replace the slave JAR on each slave that must support
approved metadata replacements, and the corresponding master lib copy. Stop the
relevant Java process before replacing loaded JARs.

This change does not create a second CRC database. The existing integrity-audit
database remains responsible for scheduled idle verification records. Recovery
approval/results use the existing persistent webadmin state; a saved CRC cannot
detect later disk corruption without a fresh disk read.
