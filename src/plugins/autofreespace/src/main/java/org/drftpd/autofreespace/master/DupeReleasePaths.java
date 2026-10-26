package org.drftpd.autofreespace.master;

import java.util.Collection;
import java.util.regex.Pattern;

/** Release roots, not archive containers or subdirectories of a release. No slave I/O. */
final class DupeReleasePaths {
    private static final Pattern RELEASE = Pattern.compile(".+-(?=[A-Za-z0-9]*[A-Za-z])[A-Za-z0-9]+");

    private DupeReleasePaths() { }

    static boolean releaseName(String name) {
        return RELEASE.matcher(name).matches();
    }

    static boolean inSection(String path, Collection<String> roots) {
        for (String root : roots) {
            String prefix = root.endsWith("/") ? root : root + "/";
            if (!path.startsWith(prefix)) continue;
            String[] parts = path.substring(prefix.length()).split("/", -1);
            if (!releaseName(parts[parts.length - 1])) continue;
            // A disc/sample (even with a group suffix) is not another release.
            boolean nested = false;
            for (int i = 0; i < parts.length - 1; i++) {
                if (releaseName(parts[i])) { nested = true; break; }
            }
            if (!nested) return true;
        }
        return false;
    }
}
