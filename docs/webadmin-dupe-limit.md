# Dupe2 review limits

The Dupe2 page has a Limit (groups) numeric field, equivalent to choosing
a result limit for a section lookup. The default is 10; allowed values are
1 through 1000. A group can contain several releases, so this is not a row
count. The limit bounds returned groups, not initial index discovery.

KEEP is green, NOT COMPLETED is orange, and Can delete is red. UNKNOWN is
also orange and cannot be selected. Completion still uses cached metadata;
this change does not trigger remote checksum reads.

Related releases outside the scanned directory are shown for comparison
but cannot be selected. Only eligible completed duplicates from the scan
are selectable, and eligibility is rechecked before confirmed WIPE.

Update both the WebAdmin and AutoFreeSpace master plugin JARs together.
No configuration or slave changes are required.
