package org.drftpd.webadmin.master;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.drftpd.master.GlobalContext;
import org.drftpd.master.event.TransferEvent;
import org.drftpd.master.indexation.AdvancedSearchParams;
import org.drftpd.master.network.BaseFtpConnection;
import org.drftpd.master.network.RemoteTransfer;
import org.drftpd.master.slavemanagement.RemoteSlave;
import org.drftpd.master.slavemanagement.SlaveManager;
import org.drftpd.master.slaveselection.filter.SlaveSelectionManager;
import org.drftpd.master.usermanager.User;
import org.drftpd.master.vfs.DirectoryHandle;
import org.drftpd.master.vfs.FileHandle;
import org.drftpd.master.vfs.InodeHandle;
import javax.net.ssl.SSLSocket;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** VFS adapter; replacements require an explicitly probed slave capability. */
final class SrrdbLibrary implements SrrdbRecovery.Library {
    private static final Logger logger = LogManager.getLogger("org.drftpd.webadmin.srrdb");
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "WebAdmin-srrDB-deadline");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean closed;
    private volatile SSLSocket activeSocket;
    private volatile RemoteTransfer activeTransfer;

    private User user(String username) throws Exception {
        if (closed || Thread.currentThread().isInterrupted()) throw new IOException("Recovery service stopped");
        User user = GlobalContext.getGlobalContext().getUserManager().getUserByNameIncludeDeleted(username);
        if (user.isDeleted() || !user.isMemberOf("siteop") || !GlobalContext.getConfig().isLoginAllowed(user)) {
            throw new IOException("Siteop access is no longer available");
        }
        return user;
    }

    private DirectoryHandle directory(User user, String path) throws IOException {
        SrrdbRecovery.validatePath(path);
        DirectoryHandle current = GlobalContext.getGlobalContext().getRoot();
        for (String part : path.split("/")) {
            if (part.isEmpty()) continue;
            InodeHandle child = current.getInodeHandle(part, user);
            if (!child.isDirectory() || child.isLink()) throw new IOException("Not a physical VFS directory: " + path);
            current = (DirectoryHandle) child;
        }
        if (current.isHidden(user)) throw new FileNotFoundException("Directory unavailable");
        return current;
    }

    @Override public List<String> releases(String username, String path, boolean recursive, int limit) throws Exception {
        User user = user(username);
        DirectoryHandle root = directory(user, path);
        if (!recursive) {
            if (root.getPath().equals("/")) throw new IOException("Select a release directory");
            return List.of(root.getPath());
        }
        AdvancedSearchParams params = new AdvancedSearchParams();
        params.setInodeType(AdvancedSearchParams.InodeType.DIRECTORY);
        params.setLimit(256);
        params.setSortField("fullPath");
        List<String> result = new ArrayList<>();
        while (result.size() < limit) {
            user(username);
            var found = GlobalContext.getGlobalContext().getIndexEngine().advancedFind(root, params, "WEBADMIN-SRRDB");
            if (found.isEmpty()) break;
            String cursor = found.keySet().stream().max(String::compareTo).orElseThrow();
            if (params.getAfterPath() != null && cursor.compareTo(params.getAfterPath()) <= 0)
                throw new IOException("Index cursor did not advance");
            for (var entry : found.entrySet()) {
                if (!entry.getValue().equals("d")) continue;
                String candidate = entry.getKey().replaceAll("/+$", "");
                String prefix = root.getPath().equals("/") ? "/" : root.getPath() + "/";
                if (!candidate.startsWith(prefix)) continue;
                try {
                    DirectoryHandle dir = directory(user, candidate);
                    if (org.drftpd.autofreespace.master.Dupe2Utils.isReleaseDirectory(dir)) result.add(dir.getPath());
                } catch (FileNotFoundException ignored) { }
                if (result.size() >= limit) break;
            }
            params.setAfterPath(cursor);
        }
        result.sort(String::compareTo);
        return result;
    }

    @Override public boolean missing(String username, String releasePath, String relativeFile) throws Exception {
        User user = user(username);
        directory(user, releasePath);
        if (!SrrdbClient.metadataPath(relativeFile)) throw new IOException("Invalid remote filename");
        String fullPath = releasePath + "/" + relativeFile;
        DirectoryHandle parent;
        try { parent = directory(user, fullPath.substring(0, fullPath.lastIndexOf('/'))); }
        catch (FileNotFoundException e) { return false; }
        String extension = relativeFile.substring(relativeFile.length() - 4).toLowerCase(Locale.ROOT);
        // An existing metadata file of the same type may have a different name.
        for (InodeHandle inode : parent.getInodeHandlesUnchecked()) {
            if (inode.getName().toLowerCase(Locale.ROOT).endsWith(extension)) return false;
        }
        return true;
    }

    @Override public SrrdbRecovery.LocalFile compare(String username, String releasePath, String relativeFile) throws Exception {
        User user = user(username);
        directory(user, releasePath);
        if (!SrrdbClient.metadataPath(relativeFile)) throw new IOException("Invalid remote filename");
        String full = releasePath + "/" + relativeFile;
        DirectoryHandle parent;
        try { parent = directory(user, full.substring(0, full.lastIndexOf('/'))); }
        catch (FileNotFoundException e) { return new SrrdbRecovery.LocalFile(false, "", -1, ""); }
        FileHandle found = null;
        String extension = relativeFile.substring(relativeFile.length() - 4);
        for (var inode : parent.getInodeHandlesUnchecked()) {
            if (inode.isHidden(user) || inode.isLink() || !(inode instanceof FileHandle file)) continue;
            if (file.getName().equalsIgnoreCase(full.substring(full.lastIndexOf('/') + 1))) { found = file; break; }
            if (found == null && file.getName().toLowerCase(Locale.ROOT).endsWith(extension.toLowerCase(Locale.ROOT))) found = file;
        }
        if (found == null) return new SrrdbRecovery.LocalFile(false, "", -1, "");
        if (found.isTransferring() || found.getSize() > 1024 * 1024)
            return new SrrdbRecovery.LocalFile(true, found.getName(), found.getSize(), "");
        try {
            long crc = freshChecksum(found, new ArrayList<>());
            return new SrrdbRecovery.LocalFile(true, found.getName(), found.getSize(),
                    String.format(Locale.ROOT, "%08X", crc));
        } catch (Exception e) {
            logger.info("srrDB metadata CRC deferred: path={} reason={}", found.getPath(), e.toString());
            return new SrrdbRecovery.LocalFile(true, found.getName(), found.getSize(), "");
        }
    }

    private record Snapshot(FileHandle file, long size, long modified, java.util.Set<RemoteSlave> slaves) {
        void check() throws Exception {
            if (file.isTransferring() || file.getSize() != size || file.lastModified() != modified
                    || !file.getSlaves().equals(slaves))
                throw new IOException("File changed or became active during verification: " + file.getPath());
            for (RemoteSlave slave : slaves) requireStable(slave);
        }
    }

    private static void requireStable(RemoteSlave slave) throws IOException {
        if (!slave.isAvailable() || slave.isRemerging() || !slave.getRenameQueue().isEmpty())
            throw new IOException("Metadata verification requires a stable holder: " + slave.getName());
    }

    private static long freshChecksum(FileHandle file, List<Snapshot> snapshots) throws Exception {
        Snapshot snapshot = new Snapshot(file, file.getSize(), file.lastModified(), java.util.Set.copyOf(file.getSlaves()));
        if (snapshot.slaves().isEmpty()) throw new IOException("No physical copy: " + file.getPath());
        snapshot.check();
        Long result = null;
        for (RemoteSlave slave : snapshot.slaves()) {
            long crc = slave.getCheckSumForPath(file.getPath());
            if (result != null && result != crc)
                throw new IOException("Physical copies disagree: " + file.getPath());
            result = crc;
            snapshot.check();
        }
        snapshot.check();
        file.setCheckSum(result);
        snapshots.add(snapshot);
        return result;
    }

    @Override public String replace(String username, String releasePath, SrrdbClient.RemoteFile remote,
                                    byte[] content, SrrdbRecovery.LocalFile approved, String transaction) throws Exception {
        User user = user(username);
        SrrdbClient.verify(remote, content);
        if (approved == null || !approved.exists() || approved.crc().isEmpty()
                || !SrrdbClient.metadataPath(approved.name()) || approved.name().contains("/"))
            throw new IOException("A fresh local metadata comparison must be approved first");
        String remotePath = releasePath + "/" + remote.name();
        DirectoryHandle parent = directory(user, remotePath.substring(0, remotePath.lastIndexOf('/')));
        InodeHandle inode = parent.getInodeHandle(approved.name(), user);
        if (!(inode instanceof FileHandle file) || inode.isLink())
            throw new IOException("Approved metadata target is no longer a regular file");
        if (!approved.name().toLowerCase(Locale.ROOT).endsWith(remote.name().substring(remote.name().length() - 4).toLowerCase(Locale.ROOT)))
            throw new IOException("Metadata type changed since approval");
        List<Snapshot> snapshots = new ArrayList<>();
        java.util.Set<RemoteSlave> holders = java.util.Set.copyOf(file.getSlaves());
        if (holders.isEmpty() || file.isTransferring()) throw new IOException("Metadata is active or has no physical copy");
        long oldCrc = Long.parseUnsignedLong(approved.crc(), 16);
        long newCrc = Long.parseUnsignedLong(remote.crc(), 16);
        for (RemoteSlave slave : holders) {
            requireStable(slave);
            String probe = slave.fetchIndex();
            slave.sendCommand(new org.drftpd.common.network.AsyncCommandArgument(probe, "maxpath", "metadata-replace-v1"));
            if (((org.drftpd.slave.network.AsyncResponseMaxPath) slave.fetchResponseWithoutDisconnect(probe, 15000)).getMaxPath()
                    != -2147482999)
                throw new IOException("Slave update required for safe metadata replacement: " + slave.getName());
            long current = slave.getCheckSumForPath(file.getPath());
            if (current != oldCrc && current != newCrc)
                throw new IOException("Metadata changed since approval on " + slave.getName());
        }
        if (remote.name().toLowerCase(Locale.ROOT).endsWith(".sfv")) {
            List<String> names = new ArrayList<>();
            for (InodeHandle child : parent.getInodeHandlesUnchecked())
                if (child instanceof FileHandle && !child.isLink()) names.add(child.getName());
            SfvReplacementGuard.verify(content, names, name -> {
                InodeHandle payload = parent.getInodeHandle(name, user);
                if (!(payload instanceof FileHandle member) || payload.isLink())
                    throw new IOException("Invalid SFV member: " + name);
                logger.info("srrDB proposed SFV payload verification: path={}", member.getPath());
                return freshChecksum(member, snapshots);
            });
        }
        // Verify all replicas before committing any; each slave also compares the approved
        // old bytes and keeps a transaction-specific private backup for retry after lost replies.
        for (RemoteSlave slave : holders) {
            user(username);
            for (Snapshot snapshot : snapshots) snapshot.check();
            requireStable(slave);
            if (file.isTransferring() || !file.getSlaves().equals(holders))
                throw new IOException("Metadata became active or holders changed; retry after inspection");
            String index = slave.fetchIndex();
            slave.sendCommand(new org.drftpd.common.network.AsyncCommandArgument(index, "replacemetadata",
                    new String[]{file.getPath(), transaction, Long.toString(approved.size()), approved.crc(),
                            remote.crc(), java.util.Base64.getEncoder().encodeToString(content)}));
            long actual = ((org.drftpd.slave.network.AsyncResponseChecksum)
                    slave.fetchResponseWithoutDisconnect(index, 30000)).getChecksum();
            if (actual != newCrc) throw new IOException("Replacement reply checksum mismatch: " + slave.getName());
        }
        file.setSize(content.length);
        file.setCheckSum(newCrc);
        if (remote.name().toLowerCase(Locale.ROOT).endsWith(".sfv")) {
            var info = SfvReplacementGuard.metadata(content, file.getName(), newCrc);
            parent.addPluginMetaData(org.drftpd.zipscript.common.sfv.SFVInfo.SFVINFO, info);
        }
        file.requestRefresh(false);
        parent.requestRefresh(false);
        logger.info("srrDB metadata replaced: path={} transaction={} copies={}", file.getPath(), transaction, holders.size());
        return holders.stream().map(RemoteSlave::getName).sorted().collect(java.util.stream.Collectors.joining(", "));
    }

    @Override public String install(String username, String releasePath, SrrdbClient.RemoteFile remote, byte[] content) throws Exception {
        User user = user(username);
        SrrdbClient.verify(remote, content);
        if (!missing(username, releasePath, remote.name())) throw new IOException("Metadata already exists; not overwritten");
        String path = releasePath + "/" + remote.name();
        DirectoryHandle parent = directory(user, path.substring(0, path.lastIndexOf('/')));
        var selection = GlobalContext.getGlobalContext().getSlaveSelectionManager();
        if (!(selection instanceof SlaveSelectionManager rules)) {
            throw new IOException("Recovery requires the configured upload filter-chain selector");
        }
        var holders = parent.getSlaveRefCounts().keySet();
        RemoteSlave destination = SrrdbUploadSelection.select(selection.getAvailableSlaves(), holders,
                rules.getFilterChain("up"), new ImportSession(user), new FileHandle(path));
        logger.info("srrDB upload destination: path={} slave={} existingHolder={} rules=slaveselection-up.conf",
                path, destination.getName(), holders.contains(destination.getName()));
        if (GlobalContext.getGlobalContext().getSSLContext() == null) throw new IOException("Master SSL is required for recovery uploads");
        RemoteTransfer transfer = null;
        FileHandle file = null;
        boolean completed = false;
        try {
            if (!SrrdbUploadSelection.eligible(destination)) {
                throw new IOException("Recovery destination became unavailable, FULL or busy: " + destination.getName());
            }
            // Do not request an optional LAN address: older slaves can have none configured.
            String index = SlaveManager.getBasicIssuer().issueListenToSlave(destination, true, false, false);
            var connection = destination.fetchTransferResponseFromIndex(index);
            transfer = destination.getTransfer(connection.getTransferIndex());
            activeTransfer = transfer;
            user(username);
            if (!missing(username, releasePath, remote.name())) throw new IOException("Metadata appeared during approval; not overwritten");
            if (!SrrdbUploadSelection.eligible(destination)) {
                throw new IOException("Recovery destination became unavailable, FULL or busy: " + destination.getName());
            }
            file = parent.createFile(user, path.substring(path.lastIndexOf('/') + 1), destination);
            try (SSLSocket socket = (SSLSocket) GlobalContext.getGlobalContext().getSSLContext().getSocketFactory().createSocket()) {
                activeSocket = socket;
                var timeout = deadlines.schedule(() -> closeSocket(socket), 60, TimeUnit.SECONDS);
                try {
                    socket.setUseClientMode(true);
                    socket.setSoTimeout(20000);
                    socket.connect(transfer.getAddress(), 10000);
                    if (destination.usePersistentInodeIdentity()) {
                        transfer.receiveFile(path, 'I', 0, "*@*", 0, 0, 0,
                                user.getName(), user.getGroup().getName(), parent.getUsername(), parent.getGroup());
                    } else {
                        transfer.receiveFile(path, 'I', 0, "*@*", 0, 0);
                    }
                    socket.startHandshake();
                    socket.getOutputStream().write(content);
                    socket.getOutputStream().flush();
                } finally { timeout.cancel(false); }
            } finally { activeSocket = null; }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (!transfer.getTransferStatus().isFinished()) {
                if (closed || Thread.currentThread().isInterrupted() || System.nanoTime() > deadline) {
                    throw new IOException("Recovery upload interrupted or timed out");
                }
                Thread.sleep(50);
            }
            if (transfer.getTransfered() != content.length) throw new IOException("Incomplete recovery upload");
            // Only checksum the small metadata file after the receiving transfer finished.
            long crc = destination.getCheckSumForPath(path);
            if (crc != Long.parseUnsignedLong(remote.crc(), 16)) throw new IOException("Slave checksum differs from approved metadata");
            file.setSize(content.length);
            file.setCheckSum(crc);
            file.setXfertime(transfer.getElapsed());
            file.requestRefresh(false);
            parent.requestRefresh(false);
            completed = true;
            // Existing missing-NFO/SFV and incomplete-link listeners use this successful upload event.
            GlobalContext.getEventService().publishAsync(new TransferEvent(new ImportSession(user), "STOR", file,
                    InetAddress.getLoopbackAddress(), destination, transfer.getAddress().getAddress(), 'I'));
            return destination.getName();
        } finally {
            if (!completed && transfer != null) transfer.abort("srrDB metadata import failed");
            activeTransfer = null;
            if (!completed && file != null) {
                logger.warn("srrDB import did not complete: {}. Inspect the reserved file before retrying; no existing physical file is deleted automatically", path);
            }
        }
    }

    private static void closeSocket(SSLSocket socket) {
        try { socket.close(); } catch (IOException ignored) { }
    }
    @Override public void close() {
        closed = true;
        SSLSocket socket = activeSocket;
        if (socket != null) closeSocket(socket);
        RemoteTransfer transfer = activeTransfer;
        if (transfer != null) transfer.abort("WebAdmin stopping");
        deadlines.shutdownNow();
    }

    private static final class ImportSession extends BaseFtpConnection {
        private final User user;
        ImportSession(User user) { this.user = user; }
        @Override public User getUserNull() { return user; }
        @Override public boolean isSecure() { return true; }
        @Override public InetAddress getClientAddress() { return InetAddress.getLoopbackAddress(); }
    }
}
