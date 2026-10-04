# srrDB recovery: empty details results

The srrDB details API can respond with HTTP 200 and an empty JSON array
when an exact release is not found. The recovery client previously assumed
every response was an object and reported this as "Invalid srrDB details response".

Recovery now counts these results separately as "not found in srrDB", logs
the release name and VFS path at INFO, and continues scanning. No review
candidate or download is created for a missing release. The count resets
on the next scan; it is scan progress, not persistent recovery state.

Malformed JSON, unexpected response shapes, mismatched release names and
invalid metadata remain errors. WARN messages identify the error category;
DEBUG includes the exception cause without logging the raw remote body.

Exact matching, release-name-only API requests, manual approval, size/CRC
verification and the original VFS import destination are unchanged.
This fix cannot recover metadata absent from srrDB.

Deployment from the combined All_Fixes build requires only the master's
build/drftpd-webadmin-master-4.0.12-SNAPSHOT.jar for this change.
Stop the master before replacing the JAR and restart afterward. Reload the
browser page to pick up the progress counter. No slave update is needed
specifically for this fix.
