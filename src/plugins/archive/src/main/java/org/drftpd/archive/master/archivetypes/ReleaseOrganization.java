package org.drftpd.archive.master.archivetypes;

import org.drftpd.common.dynamicdata.KeyNotFoundException;
import org.drftpd.master.commands.nuke.metadata.NukeData;
import org.drftpd.master.usermanager.User;
import org.drftpd.master.vfs.DirectoryHandle;
import org.drftpd.master.vfs.FileHandle;
import org.drftpd.zipscript.master.CachedCompletion;
import java.io.FileNotFoundException;
import java.util.*;
import java.util.regex.Pattern;

public final class ReleaseOrganization {
    private ReleaseOrganization() { }
    private static final Pattern EPISODE = Pattern.compile(
            "(?i)[._ -](?:S[0-9]+(?:E[0-9]+)?|E[0-9]+|OAV[0-9]+|VOL[0-9]+)(?=[._ -]|$)");
    private static final Pattern PART = Pattern.compile("(?i)[._ -]Part[._ -]*[0-9]+$");

    public static String showName(String release) {
        var matcher = EPISODE.matcher(release);
        if (!matcher.find()) return null;
        String name = PART.matcher(release.substring(0, matcher.start())).replaceFirst("");
        return name.isBlank() ? null : name;
    }

    public static boolean releaseName(String name) {
        return name.matches(".+-[A-Za-z0-9]+$")
                && (showName(name) != null || Pattern.compile(
                "(?i)[._ -](MULTI|FRENCH|SUBFRENCH|1080P|2160P|720P|WEB|BDRIP|BLURAY|DVDRIP|HDTV)[._ -]").matcher(name).find());
    }

    private static boolean blockedName(String name) {
        return name.toLowerCase(Locale.ROOT).matches("^(\\[nuked\\]|\\(incomplete\\)|\\(no_sfv\\)|\\[incomplete\\]).*");
    }

    public static boolean nuked(DirectoryHandle dir) throws FileNotFoundException {
        for (DirectoryHandle current = dir; !current.isRoot(); current = current.getParent()) {
            if (blockedName(current.getName())) return true;
            try { current.getPluginMetaData(NukeData.NUKEDATA); return true; }
            catch (KeyNotFoundException ignored) { }
        }
        return false;
    }

    /** No slave I/O: unknown metadata is never considered complete. */
    public static String unsafeReason(DirectoryHandle root) {
        try {
            if (nuked(root)) return "NUKED / INCOMPLETE MARKER";
            boolean verified = false;
            Deque<DirectoryHandle> pending = new ArrayDeque<>();
            pending.add(root);
            while (!pending.isEmpty()) {
                if (Thread.currentThread().isInterrupted()) return "INTERRUPTED";
                DirectoryHandle dir = pending.removeFirst();
                if (dir.isLink()) return "SYMLINK";
                if (nuked(dir)) return "NUKED / INCOMPLETE MARKER";
                boolean manifest = false;
                boolean payload = false;
                for (FileHandle file : dir.getFilesUnchecked()) {
                    if (file.isTransferring()) return "ACTIVE TRANSFER";
                    if (file.getXfertime() == -1 || file.getSize() == 0) return "INCOMPLETE FILE";
                    String name = file.getName().toLowerCase(Locale.ROOT);
                    manifest |= name.endsWith(".sfv") || name.endsWith(".zip");
                    payload |= !name.endsWith(".nfo") && !name.endsWith(".diz") && !name.endsWith(".sfv");
                }
                if (!manifest && payload && !Set.of("sample", "subs", "proof")
                        .contains(dir.getName().toLowerCase(Locale.ROOT))) return "UNKNOWN";
                if (manifest) {
                    var status = CachedCompletion.release(dir);
                    if (status != CachedCompletion.Status.COMPLETE) return status.name();
                    verified = true;
                }
                for (var inode : dir.getInodeHandlesUnchecked()) {
                    if (inode.isLink()) return "SYMLINK";
                    if (inode instanceof DirectoryHandle child) pending.addLast(child);
                }
            }
            return verified ? null : "UNKNOWN";
        } catch (FileNotFoundException e) { return "MISSING"; }
    }

    /** Stops descending at release roots; never follows organizational symlinks. */
    static Iterator<DirectoryHandle> releases(DirectoryHandle root, User user) {
        return new Iterator<>() {
            final Deque<DirectoryHandle> pending = new ArrayDeque<>(List.of(root));
            DirectoryHandle next;
            public boolean hasNext() {
                while (next == null && !pending.isEmpty() && !Thread.currentThread().isInterrupted()) {
                    DirectoryHandle dir = pending.removeFirst();
                    try {
                        if (dir.isLink() || (user != null && dir.isHidden(user))) continue;
                        if (!dir.equals(root) && releaseName(dir.getName())) { next = dir; break; }
                        if (nuked(dir)) continue;
                        var children = new ArrayList<DirectoryHandle>();
                        for (var inode : dir.getInodeHandlesUnchecked()) {
                            if (inode instanceof DirectoryHandle child && !inode.isLink()
                                    && !Set.of("sample", "subs", "proof").contains(inode.getName().toLowerCase(Locale.ROOT))) children.add(child);
                        }
                        children.sort(Comparator.comparing(DirectoryHandle::getPath));
                        pending.addAll(children);
                    } catch (FileNotFoundException ignored) { }
                }
                return next != null;
            }
            public DirectoryHandle next() {
                if (!hasNext()) throw new NoSuchElementException();
                DirectoryHandle value = next; next = null; return value;
            }
        };
    }
}
