# WebAdmin archive controls and durable results

## Results

Dupe2 displays every returned group on one page. Its configured/requested scan
limit still applies. Section review includes incomplete or unknown releases even
when they have no duplicate partner. Individual checkboxes allow explicit
selection of KEEP, UNKNOWN and NOT COMPLETED rows. Automatic cleanup eligibility
is unchanged. Wipe still requires confirmation and is capped at 100 selections.

Archive preview also displays all returned rows on one page. PREVIEW does not
move files. RUN uses the archive plugin's folder-reorganization handler with the
selected section, destination and template, matching the folder preview. It is
not a request to copy data to other slaves. Saved rules are not modified.
Only eligible completed, non-nuked releases are moved; existing safety checks
and destination-collision checks remain active.

PAUSE, RESUME and STOP control the archive plugin globally, including scheduled
rules. Pause/stop take effect at release boundaries: an active release finishes.
Stop invalidates pending handlers; resume does not resurrect stopped work.
These controls are in-memory; normal archive scheduling starts after a restart.
Run output records per-release outcomes, including failures and stop requests.

## Saved history

Open **Saved execution results** at the top of WebAdmin and select an execution.
History is scoped to the signed-in account and includes Dupe2 scans/actions,
archive previews/runs, manual commands and srrDB scans. Results remain available
after logout/login and restart. Automatic dashboard refreshes are excluded.
History is read-only: selecting a saved destructive operation never reruns it.
Start a fresh Dupe2 scan before acting on historical results.

Snapshots use JSON files under the `jobs` directory next to the configured
srrDB state file. They are atomically replaced where the filesystem supports it.
Changed running jobs checkpoint every five seconds, and completed jobs save
immediately. A crash can lose work since the last checkpoint. Jobs interrupted
by a master/WebAdmin restart are labelled interrupted, not automatically replayed.
The latest srrDB scan also has a `.scan.json` sidecar beside its existing state.

Retention is 30 days and up to 1000 results per account, checked during listing
and periodically during writes; snapshots are limited to 32 MiB each. Existing
command-output and scan limits still apply. Persistence failures are logged in
the existing WebAdmin/srrDB loggers. Back up this directory with master userdata.

## Deployment

No configuration or theme changes are required.
Update the combined-build archive, autofreespace and webadmin master JARs with
the master stopped, then restart it. No slave JAR changes are required.
