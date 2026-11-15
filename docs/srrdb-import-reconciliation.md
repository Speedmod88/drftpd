# Recovery import reconciliation

Before an approved missing-file import is retried, recovery inspects the target.
An existing non-empty target is accepted only if size and fresh physical-copy
checksums match the approved srrDB metadata. All known holders must be stable
and agree. Active transfers, conflicting names, links, missing physical holders
and non-empty mismatches are retained and reported for review.

The same reconciliation runs once after an upload failure. It can record a
successful import after a lost final FTP reply without writing the file again.
No blind automatic upload retry occurs. Verified files with a stale unfinished
VFS transfer marker are marked finalized only after content verification; normal
SFV metadata loading and refresh are requested.

An approved retry may remove an idle zero-byte target using normal FTPS DELE.
Its VFS size, modification time, holders and transfer state are rechecked, fresh
holder checksums must be empty, and FTP SIZE must still be zero. Non-zero files
are never deleted by this path. FTP deletion permissions still apply. PRET/STOR
then uses normal slave selection, followed by existing SIZE/CRC verification.
Do not start another upload to the same metadata path during recovery.

Only the WebAdmin master JAR changes. No config or slave update is required.
Stop the master before replacing the JAR, restart, then use Retry on the approved
failed entry. Reconciliation and zero-byte cleanup are logged in srrdb.log.
