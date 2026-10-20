# Bulk review and folder moves

## Dupe2

- Select all UNKNOWN selects loaded UNKNOWN rows across all result pages
  (up to 10,000), replacing the previous selection.
- Verify UNKNOWN in section uses the selected configured section, not the
  release-name input. Verify UNKNOWN in all sections uses all visible configured
  sections. Both discover indexed release directories with the shared Dupe2
  classifier, including releases without a duplicate partner.
- Scope verification ignores the ordinary duplicate-group result limit. It
  processes discovered UNKNOWN targets on the existing single background worker.
  The final review displays up to 1,000 groups. No files are deleted.
- Missing manifests, unavailable slaves or unfinished uploads may remain UNKNOWN.
  Normal transfer CRC behavior is unchanged. These explicit jobs read slave disks;
  they are not idle-only jobs and do not survive master restart.
- Move selected opens a section/subfolder chooser. Select a subfolder directly or
  use Open folder to browse deeper, then confirm the displayed destination.
  At most 100 releases can be moved or wiped per confirmation.

Moves use the existing RNFR/RNTO command manager with one shared session per
rename, preserving command hooks and permissions. The authenticated user must
still be a siteop. Root/section-root moves, symlinks in the source, active
transfers, existing destination names and moves into the source are refused.
Operations stop on the first failure; earlier successes are not rolled back.
This changes directory paths on their existing slaves, not archive placement
between slaves. The review is invalidated after a move or wipe.

## Archive previews

The rule dropdown lists loaded archive.conf rules. Selecting one fills its source
section, destination and folder template. The section dropdown and destination/
template fields can be overridden for a temporary preview.

Custom folder rule uses the same archive folder-template engine. Examples:

    Destination: /ARCHIVES/TV-FR
    Folder rule: rls:${rls}

    Folder rule: rls:${rls}/${rls}.Season.${season}

The datalist also offers alpha and dated:yyyy-MM. The preview applies existing
completion, nuke, collision and template checks. It never saves archive.conf,
schedules an archive operation or moves files. Rules that only relocate between
slaves need a folder destination supplied for this directory-organization preview;
it does not simulate their slave-placement decisions.

## Deployment

Update autofreespace-master and webadmin-master from the combined All_Fixes build
with the master stopped. No slave JAR, .conf, or logging XML change is needed.
Existing archive.conf rules are unchanged.
