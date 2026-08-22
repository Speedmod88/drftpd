/*
 * This file is part of DrFTPD, Distributed FTP Daemon.
 *
 * DrFTPD is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * DrFTPD is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with DrFTPD; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.drftpd.master.slavemanagement;

import com.cedarsoftware.util.io.JsonIoException;
import com.cedarsoftware.util.io.JsonWriter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.drftpd.common.dynamicdata.Key;
import org.drftpd.common.dynamicdata.KeyNotFoundException;
import org.drftpd.common.dynamicdata.KeyedMap;
import org.drftpd.common.exceptions.AsyncResponseException;
import org.drftpd.common.exceptions.DuplicateElementException;
import org.drftpd.common.exceptions.RemoteIOException;
import org.drftpd.common.network.AsyncCommand;
import org.drftpd.common.network.AsyncCommandArgument;
import org.drftpd.common.network.AsyncResponse;
import org.drftpd.common.protocol.ProtocolException;
import org.drftpd.common.slave.ConnectInfo;
import org.drftpd.common.slave.DiskStatus;
import org.drftpd.common.slave.TransferIndex;
import org.drftpd.common.slave.TransferStatus;
import org.drftpd.common.util.HostMaskCollection;
import org.drftpd.master.GlobalContext;
import org.drftpd.master.event.AsyncThreadSafeEventService;
import org.drftpd.master.event.SlaveEvent;
import org.drftpd.master.exceptions.FatalException;
import org.drftpd.master.exceptions.SlaveUnavailableException;
import org.drftpd.master.io.SafeFileOutputStream;
import org.drftpd.master.network.RemoteTransfer;
import org.drftpd.master.stats.ExtendedTimedStats;
import org.drftpd.master.usermanager.Entity;
import org.drftpd.master.vfs.CommitManager;
import org.drftpd.master.vfs.Commitable;
import org.drftpd.master.vfs.DirectoryHandle;
import org.drftpd.master.vfs.FileHandle;
import org.drftpd.master.vfs.PartialRemergeDirectoryException;
import org.drftpd.master.vfs.VirtualFileSystem;
import org.drftpd.slave.network.*;
import org.drftpd.slave.protocol.QueuedOperation;

import java.io.*;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.PatternSyntaxException;

/**
 * @author mog
 * @author zubov
 * @version $Id$
 */
public class RemoteSlave extends ExtendedTimedStats implements Runnable, Comparable<RemoteSlave>, Entity, Commitable {

    public static final Key<Boolean> SSL = new Key<>(RemoteSlave.class, "ssl");
    private static final Logger logger = LogManager.getLogger(RemoteSlave.class);
    public transient AtomicBoolean _remergePaused;
    protected transient int _errors;
    protected transient long _lastNetworkError;
    private transient boolean _isAvailable;
    private transient volatile boolean _isRemerging;
    private transient boolean _remergeChecksums;
    private transient int _prevSocketTimeout;
    private transient long _lastDownloadSending = 0L;
    private transient long _lastUploadReceiving = 0L;
    private transient long _lastResponseReceived = System.currentTimeMillis();
    private transient long _lastCommandSent = System.currentTimeMillis();
    private transient long _lastRemergeCommandReceived = 0L;
    private transient volatile long _remergeSessionStartedAt = 0L;
    private final transient AtomicLong _nextRemergeIdleReport;
    private final String _name;
    private transient DiskStatus _status;
    private HostMaskCollection _ipMasks;
    private Properties _keysAndValues;
    private final transient KeyedMap<Key<?>, Object> _transientKeyedMap;
    private ConcurrentLinkedDeque<QueuedOperation> _renameQueue;
    private transient Map<String, Integer> _queuedSourcePathCounts;
    private transient Map<String, Integer> _queuedPathCounts;
    private transient LinkedBlockingDeque<String> _indexPool;
    private transient ConcurrentHashMap<String, AsyncResponse> _indexWithCommands;
    private transient ObjectInputStream _sin;
    private transient Socket _socket;
    private transient ObjectOutputStream _sout;
    private final transient AtomicLong _connectionGeneration;
    private final transient Set<String> _abandonedCommandIndexes;
    private transient ConcurrentHashMap<TransferIndex, RemoteTransfer> _transfers;
    private transient boolean _initRemergeCompleted;
    private final transient Object _commandMonitor;
    private final transient LinkedBlockingQueue<RemergeMessage> _remergeQueue;
    private final transient LinkedBlockingQueue<FileHandle> _crcQueue;
    private transient RemergeThread _remergeThread;
    private transient CrcThread _crcThread;
    private final transient AtomicBoolean _remergeCommandRunning;
    private final transient AtomicBoolean _instantFullRemergeFallbackRequested;
    private final transient AtomicBoolean _instantFullRemergeFallbackStarted;
    private final transient AtomicBoolean _discardRemergeMessages;

    public RemoteSlave(String name) {
        _name = name;
        _keysAndValues = new Properties();
        _transientKeyedMap = new KeyedMap<>();
        _ipMasks = new HostMaskCollection();
        _renameQueue = new ConcurrentLinkedDeque<>();
        _remergePaused = new AtomicBoolean();
        _remergeQueue = new LinkedBlockingQueue<>();
        _crcQueue = new LinkedBlockingQueue<>();
        _commandMonitor = new Object();
        _remergeCommandRunning = new AtomicBoolean();
        _instantFullRemergeFallbackRequested = new AtomicBoolean();
        _instantFullRemergeFallbackStarted = new AtomicBoolean();
        _discardRemergeMessages = new AtomicBoolean();
        _connectionGeneration = new AtomicLong();
        _abandonedCommandIndexes = ConcurrentHashMap.newKeySet();
        _nextRemergeIdleReport = new AtomicLong();
    }

    public static Hashtable<String, RemoteSlave> rslavesToHashtable(Collection<RemoteSlave> rslaves) {
        Hashtable<String, RemoteSlave> map = new Hashtable<>(rslaves.size());

        for (RemoteSlave rslave : rslaves) {
            map.put(rslave.getName(), rslave);
        }

        return map;
    }

    public static String getSlaveNameFromObjectInput(ObjectInputStream in)
            throws IOException {
        try {
            Object slaveName = in.readObject();
            if (!(slaveName instanceof String)) {
                String className = slaveName == null ? "null" : slaveName.getClass().getName();
                throw new IOException("Expected slave name string, received " + className);
            }
            return (String) slaveName;
        } catch (ClassNotFoundException e) {
            throw new IOException("Unable to deserialize slave name", e);
        }
    }

    public void addMask(String mask) throws DuplicateElementException {
        _ipMasks.addMask(mask);
        commit();
    }

    /**
     * If X # of errors occur in Y amount of time, kick slave offline
     */
    public final void addNetworkError(SocketException e) {
        // set slave offline if too many network errors
        long errortimeout = Long
                .parseLong(getProperty("errortimeout", "60000")); // one
        // minute

        if (errortimeout <= 0) {
            errortimeout = 60000;
        }

        int maxerrors = Integer.parseInt(getProperty("maxerrors", "5"));

        if (maxerrors < 0) {
            maxerrors = 5;
        }

        _errors -= ((System.currentTimeMillis() - _lastNetworkError) / errortimeout);

        if (_errors < 0) {
            _errors = 0;
        }

        _errors++;
        _lastNetworkError = System.currentTimeMillis();

        if (_errors > maxerrors) {
            setOffline("Too many network errors - " + e.getMessage());
            logger.error("Too many network errors - {}", e);
        }
    }

    protected void addQueueDelete(String fileName) {
        addQueueRename(fileName, null);
    }

    protected void addQueueRename(String fileName, String destName) {
        ConcurrentLinkedDeque<QueuedOperation> renameQueue = getOrCreateRenameQueue();
        synchronized (renameQueue) {
            if (isOnline() && !isRemerging()) {
                throw new IllegalStateException(
                        "Slave is online and not remerging, you cannot queue an operation");
            }
            addQueuedOperation(fileName, destName);
        }
    }

    boolean queueDeleteIfRemerging(String fileName) {
        return queueRenameIfRemerging(fileName, null);
    }

    private boolean queueRenameIfRemerging(String fileName, String destName) {
        ConcurrentLinkedDeque<QueuedOperation> renameQueue = getOrCreateRenameQueue();
        synchronized (renameQueue) {
            if (!isRemerging()) {
                return false;
            }
            boolean added = addQueuedOperation(fileName, destName);
            if (added) {
                int queueSize = renameQueue.size();
                if (queueSize == 1 || queueSize % 100 == 0) {
                    logger.info("Queued delete/rename operation count for remerging slave {}: {}",
                            getName(), queueSize);
                } else {
                    logger.debug("Queued {} on remerging slave {}: {}{}",
                            destName == null ? "delete" : "rename", getName(), fileName,
                            destName == null ? "" : " -> " + destName);
                }
            } else {
                logger.debug("Skipped duplicate queued {} on remerging slave {}: {}{}",
                        destName == null ? "delete" : "rename", getName(), fileName,
                        destName == null ? "" : " -> " + destName);
            }
            return true;
        }
    }

    private boolean addQueuedOperation(String fileName, String destName) {
        ConcurrentLinkedDeque<QueuedOperation> renameQueue = getOrCreateRenameQueue();
        QueuedOperation operation = new QueuedOperation(fileName, destName);
        if (renameQueue.contains(operation)) {
            return false;
        }
        renameQueue.addLast(operation);
        indexQueuedOperation(operation);
        scheduleQueuedOperationsCommit();
        return true;
    }

    protected void scheduleQueuedOperationsCommit() {
        CommitManager.getCommitManager().add(this, getQueuedOperationsCommitDelay());
    }

    protected void flushQueuedOperationsCommit() {
        CommitManager commitManager = CommitManager.getCommitManager();
        commitManager.remove(this);
        commitManager.writeImmediately(this);
    }

    private long getQueuedOperationsCommitDelay() {
        String value = GlobalContext.getConfig().getMainProperties()
                .getProperty("slave.operation.queue.commit.delay", "60000");
        try {
            return Math.max(0L, Long.parseLong(value));
        } catch (NumberFormatException e) {
            logger.warn("Invalid slave.operation.queue.commit.delay '{}', using 60000", value);
            return 60000L;
        }
    }

    public void setProperty(String name, String value) {
        _keysAndValues.setProperty(name, value);
        commit();
    }

    public String getProperty(String name, String def) {
        return _keysAndValues.getProperty(name, def);
    }

    public Properties getProperties() {
        return (Properties) _keysAndValues.clone();
    }

    /**
     * Needed in order for this class to be a Bean
     */
    public void setProperties(Properties keysAndValues) {
        _keysAndValues = keysAndValues;
    }

    public KeyedMap<Key<?>, Object> getTransientKeyedMap() {
        return _transientKeyedMap;
    }

    public void commit() {
        CommitManager.getCommitManager().writeImmediately(this);
    }

    public final int compareTo(RemoteSlave o) {
        return getName().compareTo(o.getName());
    }

    public final boolean equals(Object obj) {
        return obj instanceof RemoteSlave && ((RemoteSlave) obj).getName().equals(getName());
    }

    public GlobalContext getGlobalContext() {
        return GlobalContext.getGlobalContext();
    }

    public final long getLastDownloadSending() {
        return _lastDownloadSending;
    }

    public final void setLastDownloadSending(long lastDownloadSending) {
        _lastDownloadSending = lastDownloadSending;
    }

    public final long getLastTransfer() {
        return Math.max(getLastDownloadSending(), getLastUploadReceiving());
    }

    public long getLastTransferForDirection(char dir) {
        if (dir == Transfer.TRANSFER_RECEIVING_UPLOAD) {
            return getLastUploadReceiving();
        } else if (dir == Transfer.TRANSFER_SENDING_DOWNLOAD) {
            return getLastDownloadSending();
        } else if (dir == Transfer.TRANSFER_UNKNOWN) {
            return getLastTransfer();
        } else {
            throw new IllegalArgumentException();
        }
    }

    public final long getLastUploadReceiving() {
        return _lastUploadReceiving;
    }

    public final void setLastUploadReceiving(long lastUploadReceiving) {
        _lastUploadReceiving = lastUploadReceiving;
    }

    public HostMaskCollection getMasks() {
        return _ipMasks;
    }

    public void setMasks(HostMaskCollection masks) {
        _ipMasks = masks;
    }

    /**
     * Returns the name.
     */
    public String getName() {
        return _name;
    }

    /**
     * Returns the RemoteSlave's saved SlaveStatus, can return a status before
     * remerge() is completed
     */
    public SlaveStatus getSlaveStatus() throws SlaveUnavailableException {
        if ((_status == null) || !isOnline()) {
            throw new SlaveUnavailableException();
        }
        int throughputUp = 0;
        int throughputDown = 0;
        int transfersUp = 0;
        int transfersDown = 0;
        long bytesReceived;
        long bytesSent;

        bytesReceived = getReceivedBytes();
        bytesSent = getSentBytes();

        for (RemoteTransfer transfer : _transfers.values()) {
            switch (transfer.getTransferDirection()) {
                case Transfer.TRANSFER_RECEIVING_UPLOAD:
                    throughputUp += transfer.getXferSpeed();
                    bytesReceived += transfer.getTransfered();
                    transfersUp += 1;
                    break;

                case Transfer.TRANSFER_SENDING_DOWNLOAD:
                    throughputDown += transfer.getXferSpeed();
                    transfersDown += 1;
                    bytesSent += transfer.getTransfered();
                    break;

                case Transfer.TRANSFER_UNKNOWN:
                    break;

                default:
                    throw new FatalException("unrecognized direction - "
                            + transfer.getTransferDirection() + " for " + transfer);
            }
        }

        return new SlaveStatus(_status, bytesSent, bytesReceived, throughputUp,
                transfersUp, throughputDown, transfersDown);
    }

    public long getSentBytes() {
        return Long.parseLong(getProperty("bytesSent", "0"));
    }

    public long getReceivedBytes() {
        return Long.parseLong(getProperty("bytesReceived", "0"));
    }

    /**
     * Returns the RemoteSlave's stored SlaveStatus, will not return a status
     * before remerge() is completed
     */
    public SlaveStatus getSlaveStatusAvailable()
            throws SlaveUnavailableException {
        if (isAvailable()) {
            return getSlaveStatus();
        }

        throw new SlaveUnavailableException("Slave is not online");
    }

    public final int hashCode() {
        return getName().hashCode();
    }

    /**
     * Called when the slave connects
     *
     * @throws ProtocolException
     */
    private void initializeSlaveAfterThreadIsRunning(long connectionGeneration) throws IOException,
            SlaveUnavailableException, ProtocolException {
        ensureConnectionCurrent(connectionGeneration);
        String remergeMode = GlobalContext.getConfig().getMainProperties()
                .getProperty("partial.remerge.mode", "off");
        boolean instantOnline = shouldDeferQueuedOperationsUntilAfterRemerge(remergeMode);

        commit();
        resetInstantFullRemergeFallback();
        if (instantOnline) {
            int queuedOperations = getOrCreateRenameQueue().size();
            if (queuedOperations > 0) {
                logger.info("Deferring {} queued delete/rename operation(s) for slave {} until instant remerge finishes",
                        queuedOperations, getName());
            }
        } else {
            processQueue();
        }
        ensureConnectionCurrent(connectionGeneration);

        // checking ssl availability
        String checkSSLIndex = SlaveManager.getBasicIssuer().issueCheckSSL(this);
        getTransientKeyedMap().setObject(SSL, fetchCheckSSLFromIndex(checkSSLIndex));
        ensureConnectionCurrent(connectionGeneration);

        long skipAgeCutoff = 0L;

        _remergeChecksums = GlobalContext.getConfig().getMainProperties().
                getProperty("enableremergechecksums", "false").equalsIgnoreCase("true");
        boolean partialRemerge = false;
        if (remergeMode.equalsIgnoreCase("connect")) {
            try {
                skipAgeCutoff = Long.parseLong(getProperty("lastConnect"));
                partialRemerge = true;
            } catch (NumberFormatException e) {
                logger.warn("Slave partial remerge mode set to \"off\" as lastConnect time is undefined, this may " +
                        " resolve itself automatically on next slave connection");
            }
        } else if (remergeMode.equalsIgnoreCase("disconnect")) {
            try {
                skipAgeCutoff = Long.parseLong(getProperty("lastOnline"));
                partialRemerge = true;
            } catch (NumberFormatException e) {
                logger.warn("Slave partial remerge mode set to \"off\" as lastOnline time is undefined, this may " +
                        " resolve itself automatically on next slave connection");
            }
        } else if (instantOnline) {
            skipAgeCutoff = Long.parseLong(getProperty("lastConnect"));
            if (skipAgeCutoff < 1731206543000L) {
                skipAgeCutoff = 1731206543000L;
            }

            ensureConnectionCurrent(connectionGeneration);
            setAvailable(true);
            logger.info("Slave added: '{}' status: {}", getName(), _status);
            GlobalContext.getEventService().publishAsync(new SlaveEvent("ADDSLAVE", this));
        }
        ensureConnectionCurrent(connectionGeneration);
        String remergeIndex;
        if (partialRemerge) {
            remergeIndex = SlaveManager.getBasicIssuer().issueRemergeToSlave(this, "/", true, skipAgeCutoff, System.currentTimeMillis(), false);
        } else if (instantOnline) {
            remergeIndex = SlaveManager.getBasicIssuer().issueRemergeToSlave(this, "/", true, skipAgeCutoff, System.currentTimeMillis(), true);
        } else {
            remergeIndex = SlaveManager.getBasicIssuer().issueRemergeToSlave(this, "/", false, 0L, 0L, false);
        }

        try {
            _remergeCommandRunning.set(true);
            fetchResponse(remergeIndex, 0);
        } catch (RemoteIOException e) {
            throw new IOException(e.getMessage());
        } finally {
            _remergeCommandRunning.set(false);
        }
        ensureConnectionCurrent(connectionGeneration);

        if (_instantFullRemergeFallbackRequested.get()) {
            logger.info("Initial partial remerge finished after full remerge fallback was requested for {}", getName());
            return;
        }

        setCRCThreadFinished();
        putRemergeQueue(new RemergeMessage(this));

        if (_remergePaused.get()) {
            String message = ("Remerge was paused on slave after completion, issuing resume so not to break manual remerges");
            GlobalContext.getEventService().publishAsync(new SlaveEvent("MSGSLAVE", message, this));
            logger.debug("Remerge was paused on slave after completion, issuing resume so not to break manual remerges");
            SlaveManager.getBasicIssuer().issueRemergeResumeToSlave(this);
            _remergePaused.set(false);
        }
    }

    /**
     * @return true if the slave has synchronized its filelist since last
     * connect
     */
    public boolean isAvailable() {
        return _isAvailable;
    }

    public void setAvailable(boolean available) {
        _isAvailable = available;
    }

    public boolean isAvailablePing() {
        if (!isAvailable()) {
            return false;
        }

        try {
            String index = SlaveManager.getBasicIssuer().issuePingToSlave(this);
            fetchResponse(index);
        } catch (SlaveUnavailableException e) {
            setOffline(e);
            return false;
        } catch (RemoteIOException e) {
            setOffline("The slave encountered an IOException while running ping...this is almost not possible");
            return false;
        }

        return isAvailable();
    }

    /**
     * @return true if the slave is online but a slave remerge is running
     */
    public boolean isRemerging() {
        return _isRemerging;
    }

    public void setRemerging(boolean remerging) {
        if (remerging && !_isRemerging) {
            _remergeSessionStartedAt = System.currentTimeMillis();
        } else if (!remerging) {
            _remergeSessionStartedAt = 0L;
        }
        _isRemerging = remerging;
    }

    public long getRemergeSessionStartedAt() {
        return _remergeSessionStartedAt;
    }

    /**
     * @return true if CRC is to be added on remerge for files missing CRC in VFS
     */
    public boolean remergeChecksums() {
        return _remergeChecksums;
    }

    public void processQueue() throws IOException, SlaveUnavailableException {
        ConcurrentLinkedDeque<QueuedOperation> renameQueue = getOrCreateRenameQueue();
        int queuedOperations = renameQueue.size();
        if (queuedOperations == 0) {
            return;
        }

        logger.info("Processing {} queued delete/rename operation(s) for slave {}", queuedOperations, getName());
        try {
            processQueuedOperations();
            logger.info("Finished processing queued delete/rename operation(s) for slave {}", getName());
        } finally {
            // Persist all progress once synchronously. During a long drain the
            // delayed commit also checkpoints progress at the configured interval.
            flushQueuedOperationsCommit();
        }
    }

    private void processQueuedOperations() throws IOException, SlaveUnavailableException {
        QueuedOperation item;
        ConcurrentLinkedDeque<QueuedOperation> renameQueue = getOrCreateRenameQueue();
        while ((item = renameQueue.peekFirst()) != null) {
            executeQueuedOperation(item);
            synchronized (renameQueue) {
                if (renameQueue.removeFirstOccurrence(item)) {
                    unindexQueuedOperation(item);
                    scheduleQueuedOperationsCommit();
                }
            }
        }
    }

    static boolean shouldDeferQueuedOperationsUntilAfterRemerge(String remergeMode) {
        return "instant".equalsIgnoreCase(remergeMode);
    }

    protected void executeQueuedOperation(QueuedOperation item) throws IOException, SlaveUnavailableException {
        String sourceFile = item.getSource();
        String destFile = item.getDestination();
        try {
            if (destFile == null) {
                fetchResponse(SlaveManager.getBasicIssuer().issueDeleteToSlave(this, sourceFile), 300000);
                return;
            }
            String fileName = destFile.substring(destFile.lastIndexOf("/") + 1);
            String destDir = destFile.substring(0, destFile.lastIndexOf("/"));
            fetchResponse(SlaveManager.getBasicIssuer().issueRenameToSlave(this, sourceFile, destDir, fileName));
        } catch (RemoteIOException e) {
            if (!(e.getCause() instanceof FileNotFoundException)) {
                throw e.getCause();
            }
        }
    }

    public boolean hasQueuedOperationForSourcePath(String path) {
        ConcurrentLinkedDeque<QueuedOperation> renameQueue = getOrCreateRenameQueue();
        synchronized (renameQueue) {
            return hasQueuedPathOrAncestor(path, getQueuedSourcePathCounts());
        }
    }

    public boolean hasQueuedOperationForPath(String path) {
        ConcurrentLinkedDeque<QueuedOperation> renameQueue = getOrCreateRenameQueue();
        synchronized (renameQueue) {
            return hasQueuedPathOrAncestor(path, getQueuedPathCounts());
        }
    }

    private static boolean hasQueuedPathOrAncestor(String path, Map<String, Integer> pathCounts) {
        if (path == null || pathCounts.isEmpty()) {
            return false;
        }
        String candidate = normalizeQueuedPath(path).toLowerCase(Locale.ENGLISH);
        while (true) {
            if (pathCounts.containsKey(candidate)) {
                return true;
            }
            if ("/".equals(candidate)) {
                return false;
            }
            int separator = candidate.lastIndexOf('/');
            candidate = separator <= 0 ? "/" : candidate.substring(0, separator);
        }
    }

    private static String normalizeQueuedPath(String path) {
        if (path.length() > 1 && path.endsWith("/")) {
            return path.substring(0, path.length() - 1);
        }
        return path;
    }

    /**
     * @return true if the mask was removed successfully
     */
    public final boolean removeMask(String mask) {
        boolean ret = _ipMasks.removeMask(mask);

        if (ret) {
            commit();
        }

        return ret;
    }

    protected void makeAvailableAfterRemerge() {
        _initRemergeCompleted = true;
        setProperty("lastConnect", Long.toString(System.currentTimeMillis()));
        if (!processQueueAfterRemerge()) {
            return;
        }
        if (GlobalContext.getConfig().getMainProperties().getProperty("partial.remerge.mode").equalsIgnoreCase("instant")) {
            setRemerging(false);
            GlobalContext.getEventService().publishAsync(new SlaveEvent("MSGSLAVE", "Remerge queueprocess finished", this));
        } else {
            setAvailable(true);
            setRemerging(false);
            logger.info("Slave added: '{}' status: {}", getName(), _status);
            GlobalContext.getEventService().publishAsync(new SlaveEvent("ADDSLAVE", this));
        }
    }

    public boolean processQueueAfterRemerge() {
        ConcurrentLinkedDeque<QueuedOperation> renameQueue = getOrCreateRenameQueue();
        int queuedOperations = renameQueue.size();
        if (queuedOperations == 0) {
            setRemerging(false);
            return true;
        }

        String message = "Running " + queuedOperations + " queued delete/rename operation(s) after remerge";
        logger.info("{} for slave {}", message, getName());
        GlobalContext.getEventService().publishAsync(new SlaveEvent("MSGSLAVE", message, this));
        try {
            while (true) {
                processQueue();
                synchronized (renameQueue) {
                    if (!renameQueue.isEmpty()) {
                        continue;
                    }
                    // Queue producers use this same lock when deciding whether
                    // to defer an operation, so no item can be stranded between
                    // the final empty check and leaving remerge state.
                    setRemerging(false);
                    break;
                }
            }
            logger.info("Finished queued delete/rename operations after remerge for slave {}", getName());
            return true;
        } catch (IOException e) {
            logger.error("IOException while processing queued delete/rename operations after remerge for {}", getName(), e);
            setRemerging(false);
            setOffline("IOException processing queued operations after remerge");
        } catch (SlaveUnavailableException e) {
            logger.error("Slave unavailable while processing queued delete/rename operations after remerge for {}", getName(), e);
            setRemerging(false);
            setOffline(e);
        }
        return false;
    }

    public final void setLastDirection(char direction, long l) {
        switch (direction) {
            case Transfer.TRANSFER_RECEIVING_UPLOAD -> {
                setLastUploadReceiving(l);
                return;
            }
            case Transfer.TRANSFER_SENDING_DOWNLOAD -> {
                setLastDownloadSending(l);
                return;
            }
            default -> throw new IllegalArgumentException();
        }
    }

    /**
     * Deletes files/directories and waits for the response Meant to be used if
     * you don't want to utilize asynchronization
     */
    public void simpleDelete(String path) {
        if (queueDeleteIfRemerging(path)) {
            return;
        }
        try {
            fetchResponseWithoutDisconnect(
                    SlaveManager.getBasicIssuer().issueDeleteToSlave(this, path), 300000);
        } catch (RemoteIOException e) {
            if (e.getCause() instanceof FileNotFoundException) {
                return;
            }

            addQueueDelete(path);
            if (e.getCause() instanceof SocketTimeoutException) {
                logger.warn("Delete response timed out for {} on {}; queued for retry without disconnecting slave",
                        path, getName());
                return;
            }

            setOffline("IOException deleting file, check logs for specific error");
            logger.error("IOException deleting file, file will be deleted when slave comes online", e);
        } catch (SlaveUnavailableException e) {
            // Already offline and we ARE successful in deleting the file
            addQueueDelete(path);
        }
    }

    /**
     * Renames files/directories and waits for the response
     * NOTE: We allow the destination to exist in VFS and expect the slave to 'merge' it
     */
    public void simpleRename(String from, String toDirPath, String toName) {
        String simplePath;
        if (toDirPath.endsWith("/")) {
            simplePath = toDirPath + toName;
        } else {
            simplePath = toDirPath + "/" + toName;
        }
        if (queueRenameIfRemerging(from, simplePath)) {
            return;
        }
        try {
            fetchResponse(SlaveManager.getBasicIssuer().issueRenameToSlave(this, from, toDirPath, toName));
        } catch (RemoteIOException e) {
            setOffline(e);
            addQueueRename(from, simplePath);
        } catch (SlaveUnavailableException e) {
            addQueueRename(from, simplePath);
        }
    }

    public String toString() {
        return moreInfo();
    }

    public synchronized void connect(Socket socket, ObjectInputStream in,
                                     ObjectOutputStream out) {
        long connectionGeneration = _connectionGeneration.incrementAndGet();
        _socket = socket;
        _sout = out;
        _sin = in;
        if (_indexPool == null) {
            _indexPool = new LinkedBlockingDeque<>(256);
        } else {
            _indexPool.clear();
        }

        for (int i = 0; i < 256; i++) {
            String key = Integer.toHexString(i);

            if (key.length() < 2) {
                key = "0" + key;
            }

            _indexPool.push(key);
        }

        if (_indexWithCommands == null) {
            _indexWithCommands = new ConcurrentHashMap<>();
        } else {
            _indexWithCommands.clear();
        }
        _abandonedCommandIndexes.clear();
        _nextRemergeIdleReport.set(0L);

        if (_transfers == null) {
            _transfers = new ConcurrentHashMap<>();
        } else {
            _transfers.clear();
        }

        _errors = 0;
        _lastNetworkError = System.currentTimeMillis();
        _initRemergeCompleted = false;
        setRemerging(true);
        _lastRemergeCommandReceived = System.currentTimeMillis();

        try {
            GlobalContext.getGlobalContext().getSlaveManager().getProtocolCentral().handshakeWithSlave(this);
        } catch (ProtocolException e) {
            setOfflineIfCurrent(connectionGeneration, e);
            return;
        }

        class InitiateRemergeThread implements Runnable {
            public void run() {
                try {
                    initializeSlaveAfterThreadIsRunning(connectionGeneration);
                } catch (Exception e) {
                    setOfflineIfCurrent(connectionGeneration, e);
                }
            }
        }

        new Thread(new InitiateRemergeThread(), "RemoteSlaveRemerge - " + getName()).start();
        start(connectionGeneration, socket, in);
    }

    private void start(long connectionGeneration, Socket socket, ObjectInputStream in) {
        Thread t = new Thread(() -> runConnection(connectionGeneration, socket, in));
        t.setName("RemoteSlave - " + getName());
        t.start();
    }

    public long fetchChecksumFromIndex(String index) throws RemoteIOException,
            SlaveUnavailableException {
        return ((AsyncResponseChecksum) fetchResponse(index)).getChecksum();
    }

    public String fetchIndex() throws SlaveUnavailableException {
        long connectionGeneration = _connectionGeneration.get();
        String index;
        while (isConnectionCurrent(connectionGeneration)) {
            try {
                index = _indexPool.poll(1000, TimeUnit.MILLISECONDS);
                if (index == null) {
                    logger.error("Too many commands sent, need to wait for the slave to process commands, slave:{}, lastResponse:{}", _name, _lastResponseReceived);
                } else {
                    return index;
                }
                if (getActualTimeout() < (System.currentTimeMillis() - _lastResponseReceived)) {
                    setOfflineIfCurrent(connectionGeneration,
                            "Index pool exhausted and no response from slave in "
                            + (System.currentTimeMillis() - _lastResponseReceived)
                            + " milliseconds");
                    throw new SlaveUnavailableException();
                }
            } catch (InterruptedException e1) {
            }
        }

        throw new SlaveUnavailableException("Slave was offline or went offline while fetching an index");
    }

    public int fetchMaxPathFromIndex(String maxPathIndex) throws SlaveUnavailableException {
        try {
            return ((AsyncResponseMaxPath) fetchResponse(maxPathIndex)).getMaxPath();
        } catch (RemoteIOException e) {
            throw new FatalException("Slave had an error processing maxpath");
        }
    }

    public boolean fetchCheckSSLFromIndex(String sslIndex) throws SlaveUnavailableException {
        try {
            return ((AsyncResponseSSLCheck) fetchResponse(sslIndex)).isSSLReady();
        } catch (RemoteIOException e) {
            throw new FatalException("Slave had an error processing the ssl check");
        }
    }

    public AsyncResponse fetchResponse(String index)
            throws SlaveUnavailableException, RemoteIOException {
        return fetchResponse(index, getActualTimeout());
    }

    /**
     * returns an AsyncResponse for that index and throws any exceptions thrown
     * on the Slave side
     */
    public AsyncResponse fetchResponse(String index, int wait) throws SlaveUnavailableException, RemoteIOException {
        return fetchResponse(index, wait, true);
    }

    public AsyncResponse fetchResponseWithoutDisconnect(String index, int wait)
            throws SlaveUnavailableException, RemoteIOException {
        return fetchResponse(index, wait, false);
    }

    private AsyncResponse fetchResponse(String index, int wait, boolean disconnectOnTimeout)
            throws SlaveUnavailableException, RemoteIOException {
        long connectionGeneration = _connectionGeneration.get();
        long total = System.currentTimeMillis();
        int count = 0;

        while (isConnectionCurrent(connectionGeneration) && !_indexWithCommands.containsKey(index)) {

            // Event handlers must remain nonblocking because each service has one consumer.
            if (AsyncThreadSafeEventService.isEventHandlerThread() && count > 100) {
                abandonCommandResponse(index);
                logger.warn("Deferred command reply after {} ms to keep the event FIFO moving: index={}, slave={}",
                        System.currentTimeMillis() - total, index, _name);
                throw new SlaveUnavailableException(
                        "Slave response deferred while processing an asynchronous event");
            }

            // will wait a maximum of 50 milliseconds before waking up
            // Any longer and we risk slow exchange of commands between master and slave
            try {
                synchronized (_commandMonitor) {
                    count++;
                    _commandMonitor.wait(50);
                }
            } catch (InterruptedException e) {
                // Ignore interupt
            }

            //wtf shutdown slave?
            if ((wait != 0) && ((System.currentTimeMillis() - total) >= wait)
                    && !_indexWithCommands.containsKey(index)) {

                long duration = (System.currentTimeMillis() - total);
                String timeoutMessage = "Slave has taken too long while waiting for reply " + index
                        + " | wait time: " + duration;

                if (disconnectOnTimeout) {
                    logger.error("Command response timed out: count={}, wait={}, slave={}, index={}",
                            count, duration, _name, index);
                    setOfflineIfCurrent(connectionGeneration, timeoutMessage);
                } else {
                    abandonCommandResponse(index);
                    logger.warn("Command response timed out without disconnecting slave: wait={}, slave={}, index={}",
                            duration, _name, index);
                    throw new RemoteIOException(new SocketTimeoutException(timeoutMessage));
                }
            }

            if (isRemerging()) {
                reportIdleRemergeOncePerMinute();
            }
        }

        if (!isConnectionCurrent(connectionGeneration)) {
            throw new SlaveUnavailableException("Slave went offline while processing command");
        }

        AsyncResponse rar = _indexWithCommands.remove(index);
        _indexPool.push(index);

        if (rar instanceof AsyncResponseException) {
            Throwable t = ((AsyncResponseException) rar).getThrowable();

            if (t instanceof IOException) {
                throw new RemoteIOException((IOException) t);
            }

            logger.error("Exception on slave that is unable to be handled by the master", t);
            setOffline("Exception on slave that is unable to be handled by the master");
            throw new SlaveUnavailableException("Exception on slave that is unable to be handled by the master");
        }
        return rar;
    }

    public void abandonCommandResponse(String index) {
        synchronized (_commandMonitor) {
            if (_indexWithCommands.remove(index) != null) {
                _indexPool.offer(index);
            } else {
                _abandonedCommandIndexes.add(index);
            }
        }
    }

    private void reportIdleRemergeOncePerMinute() {
        long now = System.currentTimeMillis();
        if ((now - _lastRemergeCommandReceived) < 60000L) {
            return;
        }

        long nextReport = _nextRemergeIdleReport.get();
        if (now < nextReport || !_nextRemergeIdleReport.compareAndSet(nextReport, now + 60000L)) {
            return;
        }

        logger.warn("Slave {} is still remerging but has not sent remerge data for at least 60 seconds: "
                        + "queue={}, paused={}, lastRemergeResponse={}",
                _name, _remergeQueue.size(), _remergePaused.get(), _lastRemergeCommandReceived);
    }

    public synchronized String getPASVIP() throws SlaveUnavailableException {
        if (!isOnline())
            throw new SlaveUnavailableException();

        return getProperty("pasv_addr", _socket.getInetAddress().getHostAddress());
    }

    public int getPort() {
        return _socket.getPort();
    }

    public boolean isOnline() {
        return ((_socket != null) && _socket.isConnected() && !_socket.isClosed());
    }

    private boolean isConnectionCurrent(long connectionGeneration) {
        return _connectionGeneration.get() == connectionGeneration && isOnline();
    }

    private void ensureConnectionCurrent(long connectionGeneration) throws SlaveUnavailableException {
        if (!isConnectionCurrent(connectionGeneration)) {
            throw new SlaveUnavailableException("Slave connection was replaced while initializing");
        }
    }

    private boolean isConnectionGenerationCurrent(long connectionGeneration, Socket socket) {
        return _connectionGeneration.get() == connectionGeneration && _socket == socket;
    }

    public long getCheckSumForPath(String path) throws IOException,
            SlaveUnavailableException {
        try {
            return fetchChecksumFromIndex(SlaveManager.getBasicIssuer().issueChecksumToSlave(this, path));
        } catch (RemoteIOException e) {
            throw e.getCause();
        }
    }

    public String moreInfo() {
        try {
            return getName() + ":address=[" + getPASVIP() + "]port=["
                    + getPort() + "]";
        } catch (SlaveUnavailableException e) {
            return getName() + ":offline";
        }
    }

    public void run() {
        runConnection(_connectionGeneration.get(), _socket, _sin);
    }

    private void runConnection(long connectionGeneration, Socket connectionSocket,
                               ObjectInputStream connectionInput) {
        logger.debug("Starting RemoteSlave for {}", getName());

        try {
            String pingIndex = null;
            while (isConnectionCurrent(connectionGeneration)) {
                AsyncResponse ar = null;

                try {
                    ar = readAsyncResponse(connectionGeneration, connectionSocket, connectionInput);
                    _lastResponseReceived = System.currentTimeMillis();
                } catch (SlaveUnavailableException e3) {
                    // no reason for slave thread to be running if the slave is not online
                    logger.warn("Slave unavailable catched while we were running, exiting");
                    return;
                } catch (SocketTimeoutException e) {
                    // handled below
                }

                if (!isConnectionCurrent(connectionGeneration)) {
                    return;
                }

                if ((getActualTimeout() > (System.currentTimeMillis() - _lastResponseReceived))
                        && ((getActualTimeout() / 2 < (System.currentTimeMillis() - _lastResponseReceived))
                        || (getActualTimeout() / 2 < (System.currentTimeMillis() - _lastCommandSent)))) {
                    if (pingIndex != null) {
                        logger.error("Ping lost, no response from slave, sending new ping to slave");
                        _indexPool.push(pingIndex);
                    }
                    pingIndex = SlaveManager.getBasicIssuer().issuePingToSlave(this);
                } else if (getActualTimeout() < (System.currentTimeMillis() - _lastResponseReceived)) {
                    setOfflineIfCurrent(connectionGeneration,
                            "Slave seems to have gone offline, have not received a response in "
                            + (System.currentTimeMillis() - _lastResponseReceived)
                            + " milliseconds");
                    throw new SlaveUnavailableException();
                }

                if (isOnline() && !_initRemergeCompleted) {
                    if (_remergePaused.get()) {
                        // Do we need to resume?
                        if (_remergeQueue.size() <= Integer.parseInt(GlobalContext.getConfig().getMainProperties().getProperty("remerge.resume.threshold", "50"))) {
                            _socket.setSoTimeout(_prevSocketTimeout); // Restore old time out
                            SlaveManager.getBasicIssuer().issueRemergeResumeToSlave(this);
                            _remergePaused.set(false);
                            logger.debug("Issued remerge resume to slave, current remerge queue is {}", _remergeQueue.size());
                        }
                    } else {
                        // Do we need to pause?
                        if (_remergeQueue.size() > Integer.parseInt(GlobalContext.getConfig().getMainProperties().getProperty("remerge.pause.threshold", "250"))) {
                            SlaveManager.getBasicIssuer().issueRemergePauseToSlave(this);
                            _prevSocketTimeout = _socket.getSoTimeout();
                            // Set lower timeout so it reacts faster when queueSize goes back down
                            _socket.setSoTimeout(100);
                            _remergePaused.set(true);
                            logger.debug("Issued remerge pause to slave, current remerge queue is {}", _remergeQueue.size());
                        }
                    }
                }

                if (ar == null) {
                    continue;
                }

                if (!(ar instanceof AsyncResponseRemerge) && !(ar instanceof AsyncResponseTransferStatus)) {
                    logger.debug("Received: {}", ar);
                }

                if (ar instanceof AsyncResponseTransfer) {
                    AsyncResponseTransfer art = (AsyncResponseTransfer) ar;
                    addTransfer((art.getConnectInfo().getTransferIndex()), new RemoteTransfer(art.getConnectInfo(), this));
                }

                switch (ar.getIndex()) {
                    case "Remerge" -> {
                        if (_discardRemergeMessages.get()) {
                            logger.debug("Discarding stale partial remerge message from {}", getName());
                        } else {
                            putRemergeQueue(new RemergeMessage((AsyncResponseRemerge) ar, this));
                        }
                    }
                    case "RemergeProgress" -> {
                        AsyncResponseRemergeProgress progress = (AsyncResponseRemergeProgress) ar;
                        _lastRemergeCommandReceived = System.currentTimeMillis();
                        logger.info("Slave {} remerge scan progress: directories={}, elapsed={} ms, current={}",
                                getName(), progress.getDirectoriesScanned(), progress.getElapsedMillis(),
                                progress.getPath());
                    }
                    case "DiskStatus" -> _status = ((AsyncResponseDiskStatus) ar).getDiskStatus();
                    case "TransferStatus" -> {
                        TransferStatus ats = ((AsyncResponseTransferStatus) ar).getTransferStatus();
                        RemoteTransfer rt = _transfers.get(ats.getTransferIndex());
                        if (rt == null) {
                            logger.debug("Ignoring late status for transfer {} on slave {}",
                                    ats.getTransferIndex(), getName());
                            continue;
                        }
                        rt.updateTransferStatus(ats);
                        if (ats.isFinished()) {
                            removeTransfer(ats.getTransferIndex(), rt);
                        }
                    }
                    default -> {
                        if (ar.getIndex().equals("SiteBotMessage")) {
                            String message = ((AsyncResponseSiteBotMessage) ar).getMessage();
                            GlobalContext.getEventService().publishAsync(new SlaveEvent("MSGSLAVE", message, this));
                        } else {
                            synchronized (_commandMonitor) {
                                if (_abandonedCommandIndexes.remove(ar.getIndex())) {
                                    _indexPool.offer(ar.getIndex());
                                    logger.debug("Released abandoned command index {} after its late response from {}",
                                            ar.getIndex(), getName());
                                } else {
                                    _indexWithCommands.put(ar.getIndex(), ar);
                                    if (pingIndex != null && pingIndex.equals(ar.getIndex())) {
                                        fetchResponse(pingIndex);
                                        pingIndex = null;
                                    }
                                    _commandMonitor.notifyAll();
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable e) {
            if (isConnectionGenerationCurrent(connectionGeneration, connectionSocket)) {
                logger.error("Slave thread threw an error, dropping slave offline", e);
                setOfflineIfCurrent(connectionGeneration, "error: " + e.getMessage());
            } else {
                logger.debug("Ignoring failure from stale slave connection generation {} for {}",
                        connectionGeneration, getName(), e);
            }
        }
    }

    private int getActualTimeout() {
        return Integer.parseInt(getProperty("timeout", Integer.toString(SlaveManager.actualTimeout)));
    }

    private void removeTransfer(TransferIndex transferIndex, RemoteTransfer transfer) {
        if (!_transfers.remove(transferIndex, transfer)) {
            logger.debug("Transfer {} was already removed from slave {}", transferIndex, getName());
            return;
        }
        if (transfer.getTransferDirection() == Transfer.TRANSFER_RECEIVING_UPLOAD) {
            updateDownloadedBytes(transfer.getTransfered());
        } else if (transfer.getTransferDirection() == Transfer.TRANSFER_SENDING_DOWNLOAD) {
            updateUploadedBytes(transfer.getTransfered());
        } // else, we don't care
        commit();
    }

    public void finishAbortedTransfer(TransferIndex transferIndex, RemoteTransfer transfer) {
        removeTransfer(transferIndex, transfer);
    }

    public void setOffline(String reason) {
        logger.debug("setOffline() {}", reason);
        setOfflineReal(reason);
    }

    private synchronized boolean setOfflineIfCurrent(long connectionGeneration, String reason) {
        if (_connectionGeneration.get() != connectionGeneration || _socket == null) {
            logger.debug("Ignoring setOffline from stale connection generation {} for {}: {}",
                    connectionGeneration, getName(), reason);
            return false;
        }
        setOfflineReal(reason);
        return true;
    }

    private boolean setOfflineIfCurrent(long connectionGeneration, Throwable throwable) {
        String reason = throwable.getMessage() == null ? "No Message" : throwable.getMessage();
        logger.info("setOfflineIfCurrent()", throwable);
        return setOfflineIfCurrent(connectionGeneration, reason);
    }

    private synchronized void setOfflineReal(String reason) {
        // The connection is being closed, so only reset local remerge state.
        if (isRemerging()) {
            _remergePaused.set(false);
            setRemerging(false);
        }
        // If the slave is still processing the remerge queue clear all
        // outstanding entries
        _lastRemergeCommandReceived = 0L;
        _remergeQueue.clear();
        _crcQueue.clear();
        _remergeCommandRunning.set(false);
        resetInstantFullRemergeFallback();
        if (_sin != null) {
            try {
                _sin.close();
            } catch (IOException e) {
            }
            _sin = null;
        }
        if (_sout != null) {
            try {
                _sout.flush();
                _sout.close();
            } catch (IOException e) {
            }
            _sout = null;
        }
        if (_socket != null) {
            setProperty("lastOnline", Long.toString(System.currentTimeMillis()));
            try {
                _socket.close();
            } catch (IOException e) {
            } catch (NullPointerException e) {
            }
            _socket = null;
        }
        if (_indexWithCommands != null)
            _indexWithCommands.clear();
        _abandonedCommandIndexes.clear();
        if (_transfers != null)
            _transfers.clear();
        _status = null;

        if (_isAvailable) {
            GlobalContext.getEventService().publishAsync(
                    new SlaveEvent("DELSLAVE", reason, this));
        } else {
            GlobalContext.getEventService().publishAsync(new SlaveEvent("MSGSLAVE", reason, this));
        }

        setAvailable(false);
    }

    public void setOffline(Throwable t) {
        logger.info("setOffline()", t);

        if (t.getMessage() == null) {
            setOfflineReal("No Message");
        } else {
            setOfflineReal(t.getMessage());
        }
    }

    /**
     * fetches the next AsyncResponse, if IOException is encountered, the slave
     * is setOffline() and the Exception is thrown
     *
     * @throws SlaveUnavailableException
     * @throws SocketTimeoutException
     */
    private AsyncResponse readAsyncResponse(long connectionGeneration, Socket connectionSocket,
                                            ObjectInputStream in)
            throws SlaveUnavailableException, SocketTimeoutException {
        Object obj;
        if (!isConnectionGenerationCurrent(connectionGeneration, connectionSocket)
                || connectionSocket.isClosed()) {
            throw new SlaveUnavailableException("Slave is unavailable");
        }
        while (true) {
            try {
                obj = in.readObject();
            } catch (ClassNotFoundException e) {
                logger.error("ClassNotFound reading AsyncResponse", e);
                setOfflineIfCurrent(connectionGeneration, "ClassNotFound reading AsyncResponse");
                throw new SlaveUnavailableException(
                        "Slave is unavailable - Class Not Found");
            } catch (SocketTimeoutException e) {
                // don't want this to be caught by IOException below
                throw e;
            } catch (IOException e) {
                logger.error("IOException reading AsyncResponse", e);
                setOfflineIfCurrent(connectionGeneration, "IOException reading AsyncResponse");
                throw new SlaveUnavailableException(
                        "Slave is unavailable - IOException");
            }
            if (obj != null) {
                if (obj instanceof AsyncResponse) {
                    return (AsyncResponse) obj;
                }
                String message = "Protocol violation: expected AsyncResponse, received "
                        + obj.getClass().getName();
                logger.error(message);
                setOfflineIfCurrent(connectionGeneration, message);
                throw new SlaveUnavailableException(message);
            }
        }
    }

    public ConnectInfo fetchTransferResponseFromIndex(String index)
            throws RemoteIOException, SlaveUnavailableException {
        AsyncResponseTransfer art = (AsyncResponseTransfer) fetchResponse(index);

        return art.getConnectInfo();
    }

    public synchronized void sendCommand(AsyncCommandArgument rac)
            throws SlaveUnavailableException {
        if (rac == null) {
            throw new NullPointerException();
        }

        ObjectOutputStream out = _sout;
        long connectionGeneration = _connectionGeneration.get();
        if (!isOnline()) {
            throw new SlaveUnavailableException();
        }

        try {
            logger.debug("Sending {}", rac);
            if (_indexPool != null) {
                // A reconnect can refill the pool between fetchIndex() and this write.
                _indexPool.remove(rac.getIndex());
            }
            out.writeObject(rac);
            out.flush();
            out.reset();
        } catch (IOException e) {
            logger.warn("Command write failed for slave {}; retiring connection generation {}",
                    getName(), connectionGeneration, e);
            setOfflineIfCurrent(connectionGeneration, "IOException writing command: " + e.getMessage());
            throw new SlaveUnavailableException("error sending command", e);
        }
        _lastCommandSent = System.currentTimeMillis();
    }

    public boolean checkConnect(Socket socket) throws PatternSyntaxException {
        return getMasks().check(socket);
    }

    public String getProperty(String key) {
        synchronized (_keysAndValues) {
            return _keysAndValues.getProperty(key);
        }
    }

    public void addTransfer(TransferIndex transferIndex,
                            RemoteTransfer transfer) {
        if (!isOnline()) {
            return;
        }

        _transfers.put(transferIndex, transfer);
    }

    public RemoteTransfer getTransfer(TransferIndex transferIndex)
            throws SlaveUnavailableException {
        if (!isOnline()) {
            throw new SlaveUnavailableException("Slave is not online");
        }

        RemoteTransfer ret = _transfers.get(transferIndex);
        if (ret == null) {
            if (isOnline()) {
                throw new FatalException(
                        "there is a bug somewhere in code, tried to fetch a transfer index that doesn't exist - "
                                + transferIndex);
            }
            throw new SlaveUnavailableException("Slave is not online");
        }
        return ret;
    }

    public Collection<RemoteTransfer> getTransfers()
            throws SlaveUnavailableException {
        if (!isOnline()) {
            throw new SlaveUnavailableException("Slave is not online");
        }
        return Collections.unmodifiableCollection(_transfers.values());
    }

    public boolean isMemberOf(String string) {
        StringTokenizer st = new StringTokenizer(getProperty("keywords", ""), " ");

        while (st.hasMoreElements()) {
            if (st.nextToken().equals(string)) {
                return true;
            }
        }

        return false;
    }

    public ConcurrentLinkedDeque<QueuedOperation> getRenameQueue() {
        return getOrCreateRenameQueue();
    }

    public void setRenameQueue(ConcurrentLinkedDeque<QueuedOperation> renameQueue) {
        ConcurrentLinkedDeque<QueuedOperation> loadedQueue =
                Objects.requireNonNullElseGet(renameQueue, ConcurrentLinkedDeque::new);
        LinkedHashSet<QueuedOperation> uniqueOperations = new LinkedHashSet<>();
        for (QueuedOperation operation : loadedQueue) {
            if (operation != null) {
                uniqueOperations.add(operation);
            }
        }
        _renameQueue = new ConcurrentLinkedDeque<>(uniqueOperations);
        rebuildQueuedOperationPathIndexes();
        if (!_renameQueue.isEmpty()) {
            int removedDuplicates = loadedQueue.size() - _renameQueue.size();
            logger.info("Loaded {} persisted queued delete/rename operation(s) for slave {}{}",
                    _renameQueue.size(), getName(), removedDuplicates > 0
                            ? " (removed " + removedDuplicates + " duplicate(s))" : "");
        }
    }

    private ConcurrentLinkedDeque<QueuedOperation> getOrCreateRenameQueue() {
        if (_renameQueue == null) {
            _renameQueue = new ConcurrentLinkedDeque<>();
        }
        if (_queuedSourcePathCounts == null || _queuedPathCounts == null) {
            rebuildQueuedOperationPathIndexes();
        }
        return _renameQueue;
    }

    private Map<String, Integer> getQueuedSourcePathCounts() {
        if (_queuedSourcePathCounts == null) {
            _queuedSourcePathCounts = new HashMap<>();
        }
        return _queuedSourcePathCounts;
    }

    private Map<String, Integer> getQueuedPathCounts() {
        if (_queuedPathCounts == null) {
            _queuedPathCounts = new HashMap<>();
        }
        return _queuedPathCounts;
    }

    private void rebuildQueuedOperationPathIndexes() {
        getQueuedSourcePathCounts().clear();
        getQueuedPathCounts().clear();
        if (_renameQueue != null) {
            for (QueuedOperation operation : _renameQueue) {
                indexQueuedOperation(operation);
            }
        }
    }

    private void indexQueuedOperation(QueuedOperation operation) {
        incrementQueuedPath(getQueuedSourcePathCounts(), operation.getSource());
        incrementQueuedPath(getQueuedPathCounts(), operation.getSource());
        incrementQueuedPath(getQueuedPathCounts(), operation.getDestination());
    }

    private void unindexQueuedOperation(QueuedOperation operation) {
        decrementQueuedPath(getQueuedSourcePathCounts(), operation.getSource());
        decrementQueuedPath(getQueuedPathCounts(), operation.getSource());
        decrementQueuedPath(getQueuedPathCounts(), operation.getDestination());
    }

    private static void incrementQueuedPath(Map<String, Integer> pathCounts, String path) {
        if (path != null) {
            String normalizedPath = normalizeQueuedPath(path).toLowerCase(Locale.ENGLISH);
            pathCounts.merge(normalizedPath, 1, Integer::sum);
        }
    }

    private static void decrementQueuedPath(Map<String, Integer> pathCounts, String path) {
        if (path == null) {
            return;
        }
        String normalizedPath = normalizeQueuedPath(path).toLowerCase(Locale.ENGLISH);
        pathCounts.computeIfPresent(normalizedPath, (ignored, count) -> count > 1 ? count - 1 : null);
    }

    public LinkedBlockingQueue<RemergeMessage> getRemergeQueue() {
        return _remergeQueue;
    }

    public LinkedBlockingQueue<FileHandle> getCRCQueue() {
        return _crcQueue;
    }

    public void setCRCThreadFinished() {
        if (_crcThread != null && _crcThread.isAlive()) {
            _crcThread.setFinished();
        }
    }

    private void resetInstantFullRemergeFallback() {
        _instantFullRemergeFallbackRequested.set(false);
        _instantFullRemergeFallbackStarted.set(false);
        _discardRemergeMessages.set(false);
    }

    private boolean requestInstantFullRemergeFallback(PartialRemergeDirectoryException e) {
        if (!GlobalContext.getConfig().getMainProperties().getProperty("partial.remerge.mode", "off")
                .equalsIgnoreCase("instant")) {
            return false;
        }
        if (_instantFullRemergeFallbackStarted.get()) {
            return false;
        }
        if (!_instantFullRemergeFallbackRequested.compareAndSet(false, true)) {
            return true;
        }

        _discardRemergeMessages.set(true);
        _remergeQueue.clear();
        _crcQueue.clear();
        resumeRemergeIfPaused();

        String path = e.getDirectory() + VirtualFileSystem.separator + e.getSource();
        String message = "Partial remerge failed at " + path + " (case " + e.getMergeCase()
                + "). Falling back to full instant remerge";
        logger.warn("{} for slave {}", message, getName(), e);
        GlobalContext.getEventService().publishAsync(new SlaveEvent("MSGSLAVE", message, this));

        new Thread(() -> runInstantFullRemergeFallback(e), "RemergeFallback - " + getName()).start();
        return true;
    }

    private void runInstantFullRemergeFallback(PartialRemergeDirectoryException originalFailure) {
        if (!_instantFullRemergeFallbackStarted.compareAndSet(false, true)) {
            return;
        }

        try {
            while (isOnline() && _remergeCommandRunning.get()) {
                Thread.sleep(250);
            }
            if (!isOnline()) {
                return;
            }

            _remergeQueue.clear();
            _crcQueue.clear();
            _lastRemergeCommandReceived = 0L;
            _discardRemergeMessages.set(false);

            String remergeIndex = SlaveManager.getBasicIssuer().issueRemergeToSlave(this, "/", false, 0L, 0L, true);
            try {
                _remergeCommandRunning.set(true);
                fetchResponse(remergeIndex, 0);
            } catch (RemoteIOException e) {
                throw new IOException(e.getMessage(), e);
            } finally {
                _remergeCommandRunning.set(false);
            }

            setCRCThreadFinished();
            putRemergeQueue(new RemergeMessage(this));

            if (_remergePaused.get()) {
                String message = "Remerge was paused on slave after fallback completion, issuing resume so not to break manual remerges";
                GlobalContext.getEventService().publishAsync(new SlaveEvent("MSGSLAVE", message, this));
                logger.debug(message);
                resumeRemergeIfPaused();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Interrupted during full instant remerge fallback for {}", getName(), e);
            setOffline("Interrupted during full instant remerge fallback");
        } catch (IOException e) {
            logger.error("IOException during full instant remerge fallback for {} after {}", getName(),
                    originalFailure.getMessage(), e);
            setOffline("IOException during full instant remerge fallback");
        } catch (SlaveUnavailableException e) {
            logger.error("Slave unavailable during full instant remerge fallback for {}", getName(), e);
            if (isOnline()) {
                setOffline(e);
            }
        }
    }

    private void resumeRemergeIfPaused() {
        if (_remergePaused.getAndSet(false)) {
            try {
                SlaveManager.getBasicIssuer().issueRemergeResumeToSlave(this);
            } catch (SlaveUnavailableException e) {
                // Socket may already be closed to the slave.
            }
            if (_socket != null) {
                try {
                    _socket.setSoTimeout(_prevSocketTimeout);
                } catch (SocketException e) {
                    logger.debug("Unable to restore socket timeout while resuming remerge for {}", getName(), e);
                }
            }
        }
    }

    public void shutdown() {
        try {
            sendCommand(new AsyncCommand("shutdown", "shutdown"));
            setOfflineReal("shutdown gracefully");
        } catch (SlaveUnavailableException e) {
        }
    }

    public long getLastTimeOnline() {
        if (isOnline()) {
            return System.currentTimeMillis();
        }
        String value = getProperty("lastOnline");
        // if (value == null) Slave has never been online
        return Long.parseLong(value == null ? "0" : value);
    }

    public String removeProperty(String key) throws KeyNotFoundException {
        synchronized (_keysAndValues) {
            if (getProperty(key) == null)
                throw new KeyNotFoundException();
            String value = (String) _keysAndValues.remove(key);
            commit();
            return value;
        }
    }

    public String descriptiveName() {
        return getName();
    }

    public void writeToDisk() {
        Map<String, Object> params = new HashMap<>();
        params.put(JsonWriter.PRETTY_PRINT, true);
        try (OutputStream out = new SafeFileOutputStream(
                getGlobalContext().getSlaveManager().getSlaveFile(this.getName()));
             JsonWriter writer = new JsonWriter(out, params)) {
            writer.write(this);
            logger.debug("Wrote slavefile for {}", this.getName());
        } catch (IOException | JsonIoException e) {
            throw new RuntimeException("Error writing slavefile for "
                    + this.getName() + ": " + e.getMessage(), e);
        }
    }

    public ObjectOutputStream getOutputStream() {
        return _sout;
    }

    public ObjectInputStream getInputStream() {
        return _sin;
    }

    public void putRemergeQueue(RemergeMessage message) {
        if (!message.isCompleted()) {
            logger.debug("REMERGE: putting message into queue. (path: {})", message.getDirectory());
        }
        try {
            _remergeQueue.put(message);
            _lastRemergeCommandReceived = System.currentTimeMillis();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        if (_remergeThread == null || !_remergeThread.isAlive()) {
            _remergeThread = new RemergeThread(getName());
            _remergeThread.start();
        }
    }

    public void putCRCQueue(FileHandle file) {
        logger.debug("CRC: putting file into queue {}", file.getPath());
        try {
            _crcQueue.put(file);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        if (_crcThread == null || !_crcThread.isAlive()) {
            _crcThread = new CrcThread(getName());
            _crcThread.start();
        }
    }

    private class RemergeThread extends Thread {

        public RemergeThread(String slaveName) {
            super("RemergeThread - " + slaveName);
        }

        public void run() {
            while (true) {
                RemergeMessage msg;
                try {
                    logger.info("REMERGE SIZE: {}", _remergeQueue.size());
                    msg = _remergeQueue.take();
                } catch (InterruptedException e) {
                    logger.debug("REMERGE QUE: fault in node from queue with exception {}", e.getMessage());
                    continue;
                }

                if (msg.isCompleted()) {
                    logger.info("REMERGE: queue finished");
                    resetInstantFullRemergeFallback();
                    // Wait for crc queue to finish
                    while (!_crcQueue.isEmpty()) {
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException e) {
                            logger.debug("REMERGE QUE: thread interrupted waiting for crc queue to drain with exception {}", e.getMessage());
                        }
                    }
                    if (!_initRemergeCompleted) {
                        // First remerge after slave connect
                        msg.getRslave().makeAvailableAfterRemerge();
                    }
                    break;
                }

                DirectoryHandle dir = new DirectoryHandle(msg.getDirectory());

                try {
                    dir.remerge(msg.getFiles(), msg.getRslave(), msg.getLastModified(),
                            msg.getRslave().getRemergeSessionStartedAt());
                } catch (PartialRemergeDirectoryException e) {
                    logger.warn("Partial remerge directory mismatch while remerging {}", msg.getRslave().getName(), e);
                    if (msg.getRslave().requestInstantFullRemergeFallback(e)) {
                        break;
                    }
                    logger.error("IOException during remerge", e);
                    msg.getRslave().setOffline("IOException during remerge");
                    break;
                } catch (IOException e) {
                    logger.error("IOException during remerge", e);
                    msg.getRslave().setOffline("IOException during remerge");
                    break;
                } catch (Exception e) {
			logger.error("IOException during remerge2", e);
			msg.getRslave().setOffline("IOException during remerge");
			break;
                } catch (Error e) {
			logger.error("IOException during remerge3", e);
			msg.getRslave().setOffline("IOException during remerge");
			break;
                }
            }
        }
    }

    private class CrcThread extends Thread {

        private boolean _finished = false;

        CrcThread(String slaveName) {
            super("crcThread - " + slaveName);
        }

        public void run() {
            while (true) {
                FileHandle file;
                try {
                    logger.info("REMERGE CRC SIZE: {}", _crcQueue.size());
                    file = _crcQueue.poll(1000, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    logger.debug("REMERGE CRC QUE: fault in node from queue with exception {}", e.getMessage());
                    continue;
                }
                if (_finished && _crcQueue.isEmpty() && file == null) {
                    logger.info("REMERGE CRC: queue finished");
                    break;
                }
                if (file == null) {
                    continue;
                }
                long checksum;
                try {
                    checksum = getCheckSumForPath(file.getPath());
                } catch (IOException e) {
                    logger.error("IOException on remerge getting CRC from slave [{}, {}]", getName(), file.getPath());
                    continue;
                } catch (SlaveUnavailableException e) {
                    logger.warn("Slave went offline while processing remerge crc queue.");
                    break;
                }
                try {
                    file.setCheckSum(checksum);
                } catch (FileNotFoundException e) {
                    logger.debug("File deleted while getting crc from slave {}", file.getPath());
                }
            }
        }

        void setFinished() {
            _finished = true;
        }
    }
}
