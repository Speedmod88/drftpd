# DUPE2 section discovery

WebAdmin discovers section directories in bounded 1000-entry index pages with
an exclusive fullPath cursor. It no longer inherits index.conf's default max_hits
cap before duplicate grouping. UNKNOWN verification uses the same discovery.
Visibility checks, release-root filtering and the configured duplicate group
limit remain in place. No CRC fetching or automatic deletion was added.

The section identifies the releases whose duplicate keys are searched. Matching
copies in other sections are intentionally included for cross-resolution and
cross-section comparison. UNKNOWN denotes unavailable completion evidence, not
a wrong section assignment. A section with only complete nonduplicate releases
can legitimately have no rows.

Saved scan output records the selected path and notes cross-section matching.
webadmin.log records the scope, discovered directory count and result count.
The section selector comes from the running SectionManager configuration, not
resolution text inferred from release names. Check the actual VFS path against
sections.conf: /DOCS-HD-FRENCH/1080P and /DOCS-HD-FRENCH-1080P are distinct.

No configuration changes or slave update are required. Deploy the WebAdmin JAR
with the master stopped and restart; run a new scan. Previously saved results
are not rewritten.
