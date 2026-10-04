# Reserve-aware SLAVES and DF

!slaves / SITE SLAVES and !df / SITE DF now show space above configured per-root
minfreespace reserves. Total capacity remains the physical total. A slave with
zero usable space shows ONLINE FULL (or REMERGING FULL when instant-online).
Formatting keeps the normal unit formatter: zero is shown as 0B, not 0TB.

Configure each slave in runtime/slave/config/diskselection.conf, not slave.conf:

    1.filter=minfreespace
    1.minfreespace=5GB
    1.assign=all
    1.multiplier=0

Keep any other existing selection rules, with contiguous numbering. Each slave
can have a different minimum. Use assignments such as 1 or 2 for individual
configured roots, or all for every root. If several rules match a root, the
highest minimum is used for display.

Usable space is sum(max(0, root free bytes - assigned minimum)). An unconfigured
root contributes raw free space. A root below its reserve does not subtract
space from a different healthy root. For example, a root with 8GB free and a
5GB reserve contributes 3GB; at 5GB or less it contributes zero.

IMPORTANT: multiplier=0 excludes a root AT OR BELOW the minimum for new uploads.
A positive multiplier is still scoring-only and may allow uploads below the
minimum. It was not silently converted into a blocking rule. The shipped sample
retains its existing multiplier=1; change your active configuration to 0 for a
hard reserve, then restart the slave to reload diskselection.conf.

This is not a strict allocation reservation: already-started uploads can continue
past the threshold and concurrent uploads may consume the headroom. Configure
enough reserve for expected concurrent file sizes. Downloads remain available.
An Unraid share mounted as one root exposes aggregate share space, not individual
underlying HDD space; use Unraid allocation/minimum-free settings for those disks.

The status refresh interval is still diskstatus.interval in slave.conf (default
30 seconds), plus existing transfer/delete/remerge updates. Display can lag until
the next report; the disk selection filter rechecks free space during selection.

Raw free-space getters, normal announces, AutoFreeSpace cleanup decisions, and
other internal consumers are unchanged. Only the SLAVES/SLAVE and DF command
display uses the adjusted number. The shared total retains both raw and usable
figures. diskused remains physically used bytes, so free+used may be below total
by the reserved headroom.

Custom themes using the existing status and diskfree variables work unchanged.
Additional variables in these command environments: diskfreephysical,
diskreserved (remaining protected headroom), and diskfull (FULL or empty).

Protocol compatibility: the DiskStatus serialVersionUID and raw _free/_total
fields are unchanged. Older masters ignore the new optional usable field. An old
slave supplies raw free space unless its older capacity report already says FULL,
in which case the new master can show zero. Exact reserve-adjusted numbers require
updated slaves.

## Deployment

Stop the relevant process before replacing JARs from the combined All_Fixes build:

* Master build/drftpd-master-4.0.12-SNAPSHOT.jar
* Master lib/drftpd-common-4.0.12-SNAPSHOT.jar
* Master lib/drftpd-slave-4.0.12-SNAPSHOT.jar
* Each updated slave build/drftpd-slave-4.0.12-SNAPSHOT.jar
* Each updated slave lib/drftpd-common-4.0.12-SNAPSHOT.jar

Merge the desired reserve rule into each slave's existing diskselection.conf;
do not replace active configurations with the sample wholesale. No SiteBot JAR,
theme replacement, automatic wipe, or automatic slave disconnect is introduced.
