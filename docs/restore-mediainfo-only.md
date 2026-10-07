# Restore MediaInfo-only validation

Upstream commit 345cb99a0 removed the mkvalidator dependency. The later
source overlay 2e7674f9d restored the older implementation. This fix removes
both the startup dependency and the per-MKV mkvalidator invocation again.

Startup requires only a working mediainfo --version. MKV validation reads
General compliance and Conformance errors from the existing MediaInfo output.
Incomplete samples are marked invalid and an expected size is retained when
provided. Existing MP4/AVI handling, transfer CRC checks and protocol fields
are unchanged.

This is a scoped restoration, not a wholesale replay of the upstream commit:
its additional automatic sample deletion and master-side VFS deletion are
not introduced. Inspection continues to report validity without deleting files.

Regression tests mock the external process and assert that startup and MKV
inspection each launch only mediainfo. They also cover missing/broken MediaInfo,
valid/incomplete MKVs, conformance errors and oversized expected-size values.

Deploy drftpd-mediainfo-common-4.0.12-SNAPSHOT.jar from the combined build
runtime/slave/build directory to each slave's build directory, and the matching
runtime/master/build copy to the master's build directory. Stop the respective
process before replacing its JAR, then restart. Existing MediaInfo slave/master
plugin JARs are still needed; no new configuration or mkvalidator installation
is required. This change does not require replacing the core slave/master JARs.
