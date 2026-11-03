# Compressed log archives in WebAdmin

The Logs tab lists current logs and retained archives in the configured log root,
including the standard YYYY-MM/name-YYYY-MM-DD.log.gz rotation layout. The date
selector offers Current logs, All archives and each date present in the filenames.
The file selector then lists matching log files. Monthly uncompressed .log files
are also readable. Follow is disabled for archived files.

Compressed previews return the requested last lines, using at most the last
256 KiB of expanded text. They do not extract or modify the archive. Gzip
decompression runs on a separate low-priority worker, with progress polled by
the website; the HTTP and slave command threads do not decompress archives.
Archive CRC/truncation errors return a failed preview rather than partial text
presented as a complete tail.

## Limits

- One decompression worker per master/slave process, with four queued jobs.
- At most eight retained previews; completed previews expire after five minutes.
- Requests for the same owner, file version and line count reuse a preview.
- Job IDs are scoped to the authenticated user and log scope.
- Output is bounded to 256 KiB; expanded scanning is capped at 512 MiB.
- Cancellation and a 30-second time budget are checked between read chunks.
- Oversized, corrupt or over-budget archives show a clear error. Larger files
  must be inspected outside the WebAdmin preview.
- At most 5000 files are listed, under the log root or one YYYY-MM directory.
- Path traversal, non-log files and symbolic links are refused.

These limits bound preview memory and normal processing time. Filesystem I/O
that blocks inside the OS cannot be interrupted reliably by a Java time budget.
Log rotation and retention settings are unchanged.

## Compatibility and deployment

No configuration changes are required. Remote logs still require the existing
remote.logs.enabled=true setting in slave.conf. An updated master probes archive
support before sending archive actions. Older log-capable slaves keep their
current-log list and tail; they need the updated slave JAR for archive browsing.
Disconnect/reconnect clears slave preview jobs and creates a fresh reader.

Update these files from the combined All_Fixes build:

- runtime/master/build/drftpd-webadmin-master-4.0.12-SNAPSHOT.jar
- runtime/master/lib/drftpd-slave-4.0.12-SNAPSHOT.jar
- runtime/slave/build/drftpd-slave-4.0.12-SNAPSHOT.jar

Stop the relevant process before replacing its loaded JARs, then restart it.
