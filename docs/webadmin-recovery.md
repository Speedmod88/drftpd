# WebAdmin sessions and srrDB metadata recovery

## Login

- / and /login show the login form. Successful login opens /home.
- Only existing, enabled DrFTPD users in siteop can log in.
- The server rechecks membership on each API request and before recovery work.
- Sessions expire **24 hours after login** by default, even if the browser remains
  active. Set session.timeout.minutes=1440 in the active
  config/plugins/webadmin.conf; an existing explicit value of 30 still means
  30 minutes. Requests no longer extend the expiration.
- The browser returns to login at expiry, on an authentication failure, and after
  sign-out. Restarting the master or reloading WebAdmin invalidates sessions.
- HTTPS, Secure/HttpOnly/SameSite cookies and CSRF protection remain mandatory.

## Recovery

Open **srrDB recovery**, enter an absolute VFS directory, and select one release
or indexed subdirectories. Confirm the lookup to send release names to srrDB.
The directory basename must exactly match the srrDB release; this does not use
Dupe2 title normalization or fuzzy matches.

Scans use the existing Lucene index for subdirectory discovery and VFS checks for
candidate existence. They do not traverse the slave filesystem. The default
index scan cap is 100 directories (srrdb.scan.limit, maximum 1000); narrow the
scope when a large section reaches the cap. Stored subpaths such as CD1 or
Subs must already exist and cannot be symlinks.

The review page lists missing .sfv and .nfo candidates with their destination,
source link, expected size and CRC32. A scan fetches metadata only, not the file
content. **Accept** and its confirmation approve exactly one download; **Reject**
does not download anything. No bulk approval or automatic scheduled scan is
enabled. Pending decisions and completed history are saved in
userdata/webadmin/srrdb.json; they survive master restarts. Interrupted imports
become failed review items and are never replayed automatically.

Each approved import:

1. Rechecks the approving user's siteop access and that the metadata is missing.
2. Downloads from srrDB over verified HTTPS, with response limits and timeouts.
3. Verifies content against the recorded size and CRC32, rejects HTML, invalid
   SFV syntax and unsafe filenames/member paths.
4. Chooses an online, non-remerging slave already holding the destination
   directory, with no pending rename/delete operations.
5. Reserves the filename using VFS upload permissions and transfers through the
   existing TLS slave protocol. No filesystem mount on the master is needed.
6. Checks the received byte count and performs a fresh checksum of **that small
   metadata file only, after its upload has finished**. It updates VFS and emits
   the successful upload event used by existing missing/incomplete-link handlers.

There is one background worker and a bounded queue, independent of FTP, SiteBot
and HTTP request workers. Remote requests are separated by at least one second.
The review list holds at most 500 files. Clear finished entries when necessary.
Review actions and failures are recorded in logs/webadmin.log.

## Limits and safety

- Existing metadata of the same extension in the target directory is left alone,
  including zero-byte, truncated or differently named files. This feature fills
  **missing** files; it does not replace existing corrupt metadata or archive data.
- Uploads are credited to the approving siteop in VFS. Recovery does not run the
  FTP upload accounting hooks or add race credits.
- An interrupted/failed slave upload may leave a reserved VFS entry or physical
  file. Inspect it manually before retrying. Recovery never automatically deletes
  an existing physical file, even when the index/VFS was stale.
- A fresh checksum validates the imported SFV/NFO, not every archive listed in an
  SFV. Existing CRC rescan commands remain separate.
- No slave protocol change is required. The master must be able to reach the
  slave's configured passive address and negotiate its configured data TLS.
- srrDB may not have a file or may reject a request (including anti-bot controls).
  These failures appear in the review page/log; there is no access-control bypass.
- No SRR creation/upload, pyReScene repair, archive reconstruction or standalone
  plugin has been added. The lookup/review service is isolated from HTTP so that
  a separate plugin can reuse it later.

## Deployment and verification

Stop the master, replace
runtime/master/build/drftpd-webadmin-master-4.0.12-SNAPSHOT.jar, merge the desired
settings from webadmin.conf.dist into your active webadmin.conf, and restart.
No slave JAR update is needed for this feature. Existing bind/port/certificate
settings are unchanged.

Tests:

    mvn -pl src/plugins/webadmin -am test -Dtest=WebAdmin*Test,Srrdb*Test -Dsurefire.failIfNoSpecifiedTests=false
    python tools/test-webadmin-ui.py

The optional browser test needs Python Playwright and Chrome. It uses mocked APIs,
checks desktop/mobile login, review and expiry, and never accesses a live master
or downloads files from srrDB.

API reference: https://api.srrdb.com/v1/docs
