# NFO display on CWD

A successful FTP CWD can append the current directory's NFO as 250- comments,
but only when CachedCompletion confirms COMPLETE using existing SFV/DIZ metadata.
INCOMPLETE and UNKNOWN directories never display NFO text. Completion is checked
again immediately before appending the cached lines. Displaying the NFO does not
request new checksums or alter release completion.

The master checks file visibility, download permission and the encrypted-data
policy before displaying text. It skips active, unfinished, empty, oversized and
linked NFOs. When multiple NFOs exist, it chooses the first permitted filename
in sorted order. The final 250 directory-change response remains unchanged.

## Performance

CWD reads only VFS metadata and a bounded memory cache. NFO upload events warm
the cache on a separate low-priority worker. Older releases or a cold/expired
cache are queued for a background read; that first visit displays no NFO, and
a later CWD can display it after the read finishes. There is no full archive scan.

Reads are limited to 64 KiB, one master worker and a queue of 32 jobs. Repeated
misses for the same file coalesce. Failed/unavailable reads retry after 60 seconds.
File creation time, size, last modification and cached CRC identify a cache entry,
so VFS changes such as an srrDB replacement cause a new read. Cache entries expire
and are bounded; the text is not persisted to the VFS database.

The slave handles the optional read on its existing bounded low-priority text
worker. Only regular .nfo files inside configured roots can be read; ambiguous
physical copies are skipped. The read never calculates a CRC. UTF-8 text and
classic CP437 NFOs are supported; terminal control sequences are removed before
FTP output. Lines are capped at 512 characters.

## Configuration and deployment

New master file: config/plugins/nfo-display.conf.dist. Copy it alongside the JAR
updates. An existing nfo-display.conf overrides the .dist file. Settings:

```properties
enabled=true
max.lines=200
cache.entries=256
cache.minutes=60
```

SITE RELOAD / !reload applies settings and clears the text cache. Set enabled=false
to turn off display. If the configuration file is missing or invalid, display is
disabled and CWD continues normally.

Update drftpd-zipscript-master in runtime/master/build, drftpd-slave in
runtime/master/lib, and drftpd-slave in runtime/slave/build on slaves that should
serve NFO text. Restart the master and affected slaves after replacing JARs.
There are no new slave.conf or master.conf settings.

Older slaves receive only the existing maxpath capability probe and keep working;
they do not serve the new NFO read command. No unknown command is sent to them.
