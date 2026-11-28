# Sharing the Source Safely

The November 28, 2026 privacy review replaces deployment-specific site, host,
IRC account, FTP account and slave examples with generic values in fork changes.
The corresponding fork commit messages and published branch histories are
sanitized too. Upstream history, author attribution and release tags are retained.
PR descriptions containing private deployment examples are updated separately.

Checks cover published branch tips, reachable source history and commit messages,
PR descriptions and comments, and an automated redacted secret scan. No live
credential was identified. The scanner flags an inherited public Blowfish test
fixture; public upstream defaults and explicit test placeholders are not private
deployment credentials. Scanning cannot prove the absence of every possible
secret or identify credentials already used outside this repository.

GitHub may retain old commits in closed PR diffs, cached pages and other forks.
Rewriting branch history does not purge those copies. Ask GitHub Support to remove
remaining sensitive references before treating historical PR pages as sanitized.
Rotate any real credential previously published, even after history cleanup.

Share the current All_Fixes source tree, not a deployment directory, a local Git
backup or an old clone. Runtime configurations, userdata, logs, private keys,
certificates and local audit backups must remain private. Never commit actual
FTP, IRC or WebAdmin credentials. Generic loopback/bind addresses, documentation
addresses and public service API URLs are intentionally retained.

Existing clones must not merge their old histories back into the rewritten
branches. Re-clone, or preserve local work separately and carefully migrate it.
Commit hashes in earlier PR descriptions can now refer to superseded history.

This cleanup changes no transfer, selection, permission or deletion behavior.
The WebAdmin configuration template only changes commented sample slave names
and paths. Existing runtime configuration values must not be overwritten.
