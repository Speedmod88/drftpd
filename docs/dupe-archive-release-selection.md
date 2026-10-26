# Dupe2 archive release selection

Dupe2 indexed scans now recognize nested release roots below section/archive
containers rather than assuming that every direct section child is a release.
The shared filter is used by web review, UNKNOWN discovery, FIND candidate
selection, and SITE/IRC DUPE2 indexed queries.

Release-name recognition uses the conventional final -GROUP suffix (letters
and digits, with at least one letter). This intentionally excludes numeric/date
folders, Sample, DISK1, Saison.01 and show folders such as 19-2.WEB.
It is a naming convention, not proof of completion. Nonstandard names without
that suffix are not candidates. Directories within a recognized release are
not separate duplicate candidates; existing multi-disc completion is unchanged.

For example, a section scan finds:

    /ARCHIVES/TV-HD-FRENCH/1080P/19-2.WEB/Saison.01/19-2.S01E07.FRENCH.1080p.WEB.x264-HiggsBoson

It does not return the enclosing show or season directories as releases.
Historical saved results remain historical. Run a new scan after upgrading.
Verification of an old container selection is skipped with guidance to scan
the section again.

Verification logs now distinguish active transfers, unfinished VFS transfer
markers (xfertime=-1), and empty files. These files are still not checksummed.
An UNKNOWN result means the cached SFV/DIZ/checksum evidence is insufficient;
a completed background job does not mean every release was verified complete.
No transfer marker is cleared, no file is deleted, and missing reference
metadata is not replaced with an assumed successful result.

No configuration changes or slave update are required. Update the combined
autofreespace and webadmin master JARs together while the master is stopped.
