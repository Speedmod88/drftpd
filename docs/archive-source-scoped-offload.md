# Source-scoped archive offload

MoveReleaseOffSlavesToMostFreeSlaves now queues only files with a copy on one
of the rule's offofslave.N sources. Transfers may use only those sources and
cleanup removes copies only from those sources. Other archive modes retain
their existing consolidation behavior.

slavename.N remains the destination allowlist. The existing selector chooses
the most-free eligible destinations; numofslaves specifies destination copies
for the affected files, not a cap on all copies across the site. Existing
destination copies count and do not require another transfer. Unrelated copies
are never trimmed to satisfy that count. A release is offloaded when it has no
remaining copies on the configured sources; untouched files need not be copied
to the chosen destination.

Cleanup waits for enough available destination copies and an idle file. Offline
or remerging sources remain pending until they can be cleaned up. Loss of source
availability never permits downloading from an unrelated holder. Existing CRC
and transfer settings still apply. Optional archive directory-renaming rules
are unchanged; this fix concerns physical copy placement and deletion scope.

Stop the master and deploy BOTH drftpd-jobs-master and drftpd-archive-master JARs
from the combined build, then restart. Do not mix the updated archive JAR with
an older jobs JAR. No slave, master-core JAR, library or configuration changes
are required. Existing offofslave.N and slavename.N settings are used as-is.

Example: files on Source, Other1 and Other2 with Destination selected are copied
from Source to Destination, then removed from Source only. Files held solely by
Other1/Other2 are not queued. Their existing copies remain untouched.
