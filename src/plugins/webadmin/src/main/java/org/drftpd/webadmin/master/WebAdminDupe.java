package org.drftpd.webadmin.master;

import org.drftpd.autofreespace.master.Dupe2Utils;
import org.drftpd.master.GlobalContext;
import org.drftpd.master.indexation.AdvancedSearchParams;
import org.drftpd.master.usermanager.User;
import org.drftpd.master.vfs.DirectoryHandle;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

final class WebAdminDupe {
    private WebAdminDupe() { }

    static User user(String name) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new IOException("Job stopped");
        User user = GlobalContext.getGlobalContext().getUserManager().getUserByNameIncludeDeleted(name);
        if (user.isDeleted() || !user.isMemberOf("siteop") || !GlobalContext.getConfig().isLoginAllowed(user)) {
            throw new IOException("Siteop access is no longer available");
        }
        return user;
    }

    static DirectoryHandle directory(User user, String path) throws IOException {
        SrrdbRecovery.validatePath(path);
        DirectoryHandle current = GlobalContext.getGlobalContext().getRoot();
        for (String part : path.split("/")) {
            if (part.isEmpty()) continue;
            var child = current.getInodeHandle(part, user);
            if (!child.isDirectory() || child.isLink()) throw new IOException("Not a physical VFS directory: " + path);
            current = (DirectoryHandle) child;
        }
        if (current.isHidden(user)) throw new IOException("Directory unavailable");
        return current;
    }

    static int limit(Integer value) {
        int limit = value == null ? 10 : value;
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Limit must be between 1 and 1000");
        return limit;
    }

    static List<Dupe2Utils.ReviewResult> scan(String username, String path, int limit) throws Exception {
        User user = user(username);
        DirectoryHandle root = directory(user, path);
        AdvancedSearchParams params = new AdvancedSearchParams();
        params.setInodeType(AdvancedSearchParams.InodeType.DIRECTORY);
        params.setSortField("fullPath");
        var indexed = GlobalContext.getGlobalContext().getIndexEngine().advancedFind(root, params, "WEBADMIN-DUPE2");
        List<String> candidates = new ArrayList<>();
        String prefix = root.getPath().equals("/") ? "/" : root.getPath() + "/";
        for (var entry : indexed.entrySet()) {
            String candidate = entry.getKey().replaceAll("/+$", "");
            if (entry.getValue().equals("d") && candidate.startsWith(prefix)) {
                try { candidates.add(directory(user, candidate).getPath()); }
                catch (IOException ignored) { }
            }
            if (Thread.currentThread().isInterrupted()) throw new IOException("Scan stopped");
        }
        List<Dupe2Utils.ReviewResult> results = new ArrayList<>();
        for (var result : Dupe2Utils.reviewDuplicates(candidates, limit)) {
            try { directory(user, result.path()); results.add(result); }
            catch (IOException ignored) { }
        }
        return results;
    }

    static boolean eligible(String username, String path) throws Exception {
        directory(user(username), path);
        return Dupe2Utils.getDupeLoserPaths(List.of(path)).contains(path);
    }

    static List<String> selection(Collection<String> offered, List<String> selected) {
        if (selected == null || selected.isEmpty() || selected.size() > 100) {
            throw new IllegalArgumentException("Select between 1 and 100 releases");
        }
        List<String> unique = selected.stream().distinct().toList();
        for (String path : unique) {
            if (path == null || path.equals("/") || !offered.contains(path)) {
                throw new IllegalArgumentException("Selection contains a path outside this scan");
            }
            SrrdbRecovery.validatePath(path);
        }
        return unique;
    }
}
