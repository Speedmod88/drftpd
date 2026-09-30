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

/** VFS adapter. No local filesystem assumptions and no slave protocol additions. */
final class SrrdbLibrary implements SrrdbRecovery.Library {
    private static final Logger logger = LogManager.getLogger(SrrdbLibrary.class);
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
        params.setLimit(limit);
        params.setSortField("fullPath");
        var found = GlobalContext.getGlobalContext().getIndexEngine().advancedFind(root, params, "WEBADMIN-SRRDB");
        List<String> result = new ArrayList<>();
        for (var entry : found.entrySet()) {
            if (!entry.getValue().equals("d")) continue;
            String candidate = entry.getKey().replaceAll("/+$", "");
            String prefix = root.getPath().equals("/") ? "/" : root.getPath() + "/";
            if (!candidate.startsWith(prefix)) continue;
            try {
                DirectoryHandle dir = directory(user, candidate);
                if (dir.getName().contains("-")) result.add(dir.getPath());
            } catch (FileNotFoundException ignored) { }
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

    @Override public String install(String username, String releasePath, SrrdbClient.RemoteFile remote, byte[] content) throws Exception {
        User user = user(username);
        SrrdbClient.verify(remote, content);
        if (!missing(username, releasePath, remote.name())) throw new IOException("Metadata already exists; not overwritten");
        String path = releasePath + "/" + remote.name();
        DirectoryHandle parent = directory(user, path.substring(0, path.lastIndexOf('/')));
        RemoteSlave destination = null;
        for (String name : parent.getSlaveRefCounts().keySet().stream().sorted().toList()) {
            RemoteSlave slave = GlobalContext.getGlobalContext().getSlaveManager().getRemoteSlave(name);
            if (slave.isAvailable() && !slave.isRemerging() && slave.getRenameQueue().isEmpty()) {
                destination = slave;
                break;
            }
        }
        if (destination == null) throw new IOException("No stable online slave holding this directory; retry after remerge");
        if (GlobalContext.getGlobalContext().getSSLContext() == null) throw new IOException("Master SSL is required for recovery uploads");
        RemoteTransfer transfer = null;
        FileHandle file = null;
        boolean completed = false;
        try {
            // Do not request an optional LAN address: older slaves can have none configured.
            String index = SlaveManager.getBasicIssuer().issueListenToSlave(destination, true, false, false);
            var connection = destination.fetchTransferResponseFromIndex(index);
            transfer = destination.getTransfer(connection.getTransferIndex());
            activeTransfer = transfer;
            user(username);
            if (!missing(username, releasePath, remote.name())) throw new IOException("Metadata appeared during approval; not overwritten");
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
