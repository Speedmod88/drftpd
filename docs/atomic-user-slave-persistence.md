# Atomic userdata persistence

## Behavior

User and slave JSON saves, and the existing group, VFS and nuke JSON callers of
SafeFileOutputStream, now use an explicit successful-serialization callback.
Closing the JSON writer flushes its staged stream but cannot publish a partial
object when serialization subsequently throws. Persisted JSON formats are unchanged.

Each save stages a uniquely named temporary file in the destination directory.
Only the final replacement holds a reference-counted JVM lock keyed by the
normalized absolute destination path. Locks are removed when no writers remain.
Serialization runs outside filesystem locks to avoid lock-order deadlocks with
live user/slave metadata. Unrelated targets can commit concurrently.

The completed temporary replaces the target with Files.move using ATOMIC_MOVE
and REPLACE_EXISTING. The code never deletes the destination first and never
falls back to delete/rename or a non-atomic replacement. If the filesystem does
not support atomic replacement, the save fails with its underlying cause logged.

SafeFileWriter uses the same staging and commit implementation for UTF-8 text.
The binary API retains its historical behavior that an untouched stream does not
replace an existing file; the text API may intentionally save an empty file.
Bulk byte writes are forwarded directly rather than written one byte at a time.

## Temporary files and failures

- Successful replacement consumes the newly staged temporary file.
- Serialization, write, or close failures discard only that save's incomplete
  temporary, without replacing the target. Cleanup failures are logged.
- Atomic replacement failure retains the completed temporary for manual recovery
  and logs both target and recoveryTemporary paths, with the original exception.
  No destination deletion is attempted. Persistent filesystem failures can thus
  still leave recovery files deliberately; they are no longer silent leftovers.
- Existing temporary files are never scanned, restored, deleted or overwritten.
  Old userdata snapshots may contain account information, credits and slave queues.

I/O-layer failure messages are emitted under org.drftpd.master.io and normally go
to master.log. Existing CommitManager context errors still go to vfs.log with the
usual logging setup. No serialized contents or passwords are logged by the writer.

## Scope and limits

Locks coordinate writers inside one JVM, not separate master processes or other
applications. They protect replacement, not snapshot isolation of all mutable
object fields. Existing domain mutations and save scheduling remain unchanged.
Atomic replacement is not a power-loss durability guarantee. No per-save fsync,
global save lock, extra background timer or automatic old-temp cleanup is added.
A forcibly terminated process can still leave an in-progress temporary file.

Filesystems and Windows reader handles may reject an atomic replacement. The
writer then preserves recovery data and reports the failure rather than deleting
the current destination or pretending the save succeeded.

## Deployment

Master restart required. Update only runtime/master/build/drftpd-master-4.0.12-SNAPSHOT.jar
from the combined All_Fixes build. No configuration, slave, plugin JAR or JSON
format updates are required. Keep ordinary userdata backups; do not restore old
temporary snapshots over current JSON files without manual comparison.

## Verification

Regression tests cover serialization exceptions/errors, premature serializer close,
write failure, explicit abort, repeated close, empty and UTF-8 writes, failed
replacement with recovery retention, concurrent normalized-path writers, open
readers, lock release and JSON round trips. Core regression tests exercise existing
user/slave/VFS behavior with the unchanged serialized representation.
