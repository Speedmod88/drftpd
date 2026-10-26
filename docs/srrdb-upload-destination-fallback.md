# srrDB recovery upload destination

Missing approved NFO/SFV files can now be uploaded to another eligible slave,
even when every existing holder is unavailable or remerging. The VFS combines
the new metadata with the release files already held on other slaves.

Recovery evaluates the normal slaveselection-up.conf filter chain against the
full target filename and approving user. It excludes offline, remerging, FULL
and pending-operation slaves. Among the remaining candidates, it prefers the
highest-scored existing holder, otherwise the highest-scored eligible slave.
Holder preference never restores a slave removed by upload rules.

The slave still applies diskselection.conf and creates the physical parent
directories through the existing upload protocol. Capacity is checked again
before opening the listener and reserving the VFS file. State can still change
during a transfer; normal slave-side upload failure handling remains in place.

Existing metadata is never intentionally overwritten. Approval, downloaded
size/CRC verification, post-upload CRC verification, VFS registration and
successful upload events remain unchanged. Failed entries can be accepted again;
this change does not add automatic retries or RAR repair.

No configuration changes or slave update are required. Replace the combined
webadmin master JAR while the master is stopped. Custom selection managers
without the normal filter-chain implementation fail closed rather than bypass
upload rules.
