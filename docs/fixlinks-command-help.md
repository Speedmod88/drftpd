# FIXLINKS modes

!fixlinks, or SITE FIXLINKS after CWD in FTP, recursively visits the current
directory and descendants. Configured ALL-SFV-MISSING-*, ALL-SFV-INCOMPLETE-*
and ALL-ZIP-INCOMPLETE-* collections are skipped by this mode.

!fixlinks best or SITE FIXLINKS best checks existing SFV-missing, SFV-incomplete
and ZIP-incomplete links, including configured global collections. best comes
from the legacy runFixLinksBest implementation name. It is a convention only:
the code switches mode on ANY argument and never interprets an argument as a
directory path. Global collections need not be within the current directory.

Both modes run in the background. They may fetch metadata from slaves and
create/delete links, not release payload files. Avoid concurrent scans. Check
master logs for Site Fixlinks - Finished or runFixLinksBest COMPLETED.

The update adds detailed command help; it does not change this legacy behavior.
