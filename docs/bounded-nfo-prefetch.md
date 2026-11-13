# Bounded NFO prefetch

Replaces the general FIND range query and sorted collection used for each NFO
prefetch batch with a direct walk of the committed Lucene fullPath dictionary.
Each batch visits at most 2048 path terms and returns at most prefetch.batch NFO
files. Deleted entries and directories are ignored; uppercase NFO suffixes work.
An empty batch still advances the persistent cursor unless the dictionary ended.
No index schema change or rebuild is required.

Background discovery does not force the live index writer to flush. Recently
indexed paths become discoverable after the normal index commit. Upload-triggered
caching and on-demand CWD retrieval remain independent of background discovery.
Paths inserted behind the cursor are revisited on the next complete pass.

Discovery pauses while any slave is remerging and retains the existing FIFO3
pressure guard. The cooldown starts after the batch finishes and is the larger
of the configured interval or nine times the elapsed batch duration. This is
cooperative pacing, not an OS CPU or I/O quota, and cannot interrupt a filesystem
call already in progress. Foreground NFO requests still interrupt between
candidates without advancing the cursor past unprocessed candidates.

The private NFO store's pruning pages also use dictionary seeks rather than
range queries and sorting. Nonempty pruning batches are spaced ten seconds apart.
Explicit deletion invalidations and batched durability commits are retained.

Deploy both drftpd-master and drftpd-zipscript-master JARs together, with the master
stopped. No slave or dependency updates. nfo-display.conf.dist has explanatory
comments only; existing active settings remain valid. If prefetch was temporarily
disabled for diagnosis, set prefetch.enabled=true and reload after deployment.

This addresses observed prefetch overhead. It does not change CWD race-statistics
SFV discovery or establish that every reported upload timeout has the same cause.
