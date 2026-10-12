# Queued filesystem failures

Remote delete/rename filesystem errors no longer force a slave disconnect and
another complete remerge. The head operation remains persisted and is retried
after 60, 120, 240, then at most every 300 seconds. Later operations cannot
overtake it. FileNotFoundException retains the existing already-removed behavior.

Retries use the existing per-slave queue-processing worker, not the master timer
or slave response reader. Instant-online transfers remain available. The slave
remains marked remerging until deferred operations finish; no false completion
announcement is emitted while operations remain blocked. Network failures still
follow normal disconnection handling. Retry waits stop when the connection ends
or changes, and reconnect work is handed to a new remerge worker.

Warnings include source, destination and the filesystem exception. MSGSLAVE
announcements report the retry delay. Existing slave-file persistence is kept;
there is no queue format or wire-protocol change.

This does not repair permissions, exhausted storage or VirtioFS errors. Correct
the underlying filesystem problem and the next retry will continue the queue.

Deployment: update the master JAR from the combined All_Fixes build with the
master stopped, then restart it. No slave JAR or configuration change is needed.
