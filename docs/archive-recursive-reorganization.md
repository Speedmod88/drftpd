# Recursive archive reorganization

Use a section pointing at the archive tree. The scanner walks mixed directory
depths and stops at release folders, without following symlinks.

```properties
1.type=MoveReleaseToSpecificFolder
1.section=ARCHIVE-TV-FR
1.recursive=true
1.preview=true
1.archiveafter=0
1.todirectory=/ARCHIVES/TV-FR
1.todirectorytype=rls:${rls}
1.repeat=30
```

Use the next contiguous rule number in archive.conf. Recursive rules default
to preview-only. Set preview=false only after reviewing the destinations.
The WebAdmin Archive tab always previews, even when the automatic rule is active.
Its limit caps reviewed releases, not the number of directories traversed.

The existing destination templates remain available. Show-name extraction keeps
the title before S01E1005, E1005, OAV01 or VOL26 and removes a trailing Part.2.
Already sorted releases and destination collisions are skipped; no overwrite
or empty-parent deletion is performed. Recursive traversal is breadth-first,
not oldest-first.

All archive moves require completed cached SFV/DIZ metadata. Unknown,
incomplete, nuked, symlinked or actively transferring releases are skipped.
No CRC scans or remote manifest downloads are initiated by preview.
Existing transfers and CRC validation behavior are unchanged.

Deploy the archive and webadmin master JARs together. No slave update is needed.
New configuration keys are recursive and preview; existing active configuration
files are not rewritten.
