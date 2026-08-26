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
package org.drftpd.slave;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.drftpd.common.exceptions.SSLServiceException;
import org.drftpd.common.network.SSLGetContext;
import org.drftpd.common.network.SSLService;
import org.elasticsearch.common.ssl.SslConfiguration;
import org.elasticsearch.common.ssl.SslConfigurationLoader;

import org.drftpd.common.exceptions.AsyncResponseException;
import org.drftpd.common.exceptions.SSLUnavailableException;
import org.drftpd.common.io.PermissionDeniedException;
import org.drftpd.common.io.PhysicalFile;
import org.drftpd.common.network.AsyncCommandArgument;
import org.drftpd.common.network.AsyncResponse;
import org.drftpd.common.slave.DiskStatus;
import org.drftpd.common.slave.TransferIndex;
import org.drftpd.common.util.ConfigLoader;
import org.drftpd.common.util.PortRange;
import org.drftpd.common.util.PropertyHelper;
import org.drftpd.slave.diskselection.DiskSelectionInterface;
import org.drftpd.slave.exceptions.FileExistsException;
import org.drftpd.slave.network.AsyncResponseDiskStatus;
import org.drftpd.slave.network.AsyncResponseMaxPath;
import org.drftpd.slave.network.AsyncResponseRemerge;
import org.drftpd.slave.network.AsyncResponseSSLCheck;
import org.drftpd.slave.network.AsyncResponseTransfer;
import org.drftpd.slave.network.AsyncResponseTransferStatus;
import org.drftpd.slave.network.Transfer;
import org.drftpd.slave.protocol.SlaveProtocolCentral;
import org.drftpd.slave.vfs.Root;
import org.drftpd.slave.vfs.RootCollection;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocket;
import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.NoSuchFileException;
import java.security.GeneralSecurityException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;

/**
 * @author mog
 * @author zubov
 * @version $Id$
 */
public class Slave extends SslConfigurationLoader {

    public static final boolean isWindows = System.getProperty("os.name").startsWith("Windows");

    public static final String VERSION = "DrFTPD 4.0.12-git";

    private static final String SETTING_PREFIX = "master.ssl.";

    private static final Logger logger = LogManager.getLogger(Slave.class);

    private static final int socketTimeout = 10000; // 10 seconds, for Socket

    private static final int actualTimeout = 60000; // one minute, evaluated on a SocketTimeout

    private static final int RESPONSE_PRIORITY_CONTROL = 0;
    private static final int RESPONSE_PRIORITY_STATUS = 1;
    private static final int RESPONSE_PRIORITY_DISK = 2;
    private static final int RESPONSE_PRIORITY_REMERGE = 3;
    private static final int MAX_QUEUED_REMERGE_RESPONSES = 16;

    private int _bufferSize;

    private int _maxPathLength;

    private String[] _cipherSuites;

    private String[] _sslProtocols;

    private SslConfiguration _sslConfig;

    private boolean _downloadChecksums;

    private RootCollection _roots;

    private SSLSocket _socket;

    private ObjectInputStream _sin;

    private ObjectOutputStream _sout;

    private final PriorityBlockingQueue<QueuedResponse> _responseQueue = new PriorityBlockingQueue<>();

    private final Semaphore _remergeResponseSlots =
            new Semaphore(MAX_QUEUED_REMERGE_RESPONSES, true);

    private final AtomicInteger _pendingRemergeResponses = new AtomicInteger();

    private final AtomicLong _responseSequence = new AtomicLong();

    private final AtomicReference<RuntimeException> _responseWriterFailure = new AtomicReference<>();

    private final Object _responseWriterMonitor = new Object();

    private volatile boolean _responseWriterRunning;

    private Thread _responseWriterThread;

    private final ThreadPoolExecutor _controlCommandExecutor;

    private final ThreadPoolExecutor _transferCommandExecutor;

    private final ThreadPoolExecutor _filesystemCommandExecutor;

    private final ThreadPoolExecutor _checksumCommandExecutor;

    private final ThreadPoolExecutor _remergeCommandExecutor;

    private Map<TransferIndex, Transfer> _transfers;

    private boolean _uploadChecksums;

    private PortRange _portRange;

    private int _timeout;

    private SlaveProtocolCentral _central;

    private DiskSelectionInterface _diskSelection = null;

    private boolean _ignorePartialRemerge;

    private int _rootCollectionThreads;

    private boolean _concurrentRootIteration;

    private InetAddress _bindIP;

    private InetAddress _slaveLanIP;

    private boolean _online;

    private Properties _cfg;

    public Slave(Properties p) throws IOException, SSLUnavailableException {
        super(SETTING_PREFIX);
        _cfg = p;
        _controlCommandExecutor = createCommandExecutor("Slave Control Command", p,
                "command.control.threads", 8, "command.control.queue", 256);
        _transferCommandExecutor = createCommandExecutor("Slave Transfer Command", p,
                "command.transfer.threads", 256, "command.transfer.queue", 256);
        _filesystemCommandExecutor = createCommandExecutor("Slave Filesystem Command", p,
                "command.filesystem.threads", 8, "command.filesystem.queue", 256);
        _checksumCommandExecutor = createCommandExecutor("Slave Checksum Command", p,
                "command.checksum.threads", 2, "command.checksum.queue", 256);
        _remergeCommandExecutor = createCommandExecutor("Slave Remerge Command", p,
                "command.remerge.threads", 1, "command.remerge.queue", 1);
        try {
            _sslConfig = load(Paths.get(""));
            SSLService.getSSLService().registerSSLConfiguration(SETTING_PREFIX, _sslConfig);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        logger.info("Master connection SSL/TLS initialized as: {}", _sslConfig.toString());
        String masterHost = PropertyHelper.getProperty(p, "master.host");
        int masterPort;
        try {
            masterPort = Integer.parseInt(PropertyHelper.getProperty(p, "master.bindport"));
        } catch(NumberFormatException e) {
            logger.error("Unable to parse port from configuration", e);
            throw new RuntimeException(e);
        }
        InetSocketAddress masterIsa = new InetSocketAddress(masterHost, masterPort);

        // Whatever interface the slave uses to connect to the master, is the
        // interface that the master will report to clients requesting PASV
        // transfers from this slave, unless pasv_addr is set on the master for this
        // slave
        String slaveName = PropertyHelper.getProperty(p, "slave.name");

        // Initialize to null
        _bindIP = null;
        try {
            String bindIP = PropertyHelper.getProperty(p, "bind.ip", "");
            logger.debug("'bind.ip' has been resolved to {}", bindIP);
            if (bindIP.length() > 0) {
                _bindIP = InetAddress.getByName(bindIP);
            }
        } catch(UnknownHostException e) {
            logger.warn("'bind.ip' is not a valid ip address");
        } catch(Exception e) {
            logger.error("Unknown error occurred trying to get 'bind.ip' config", e);
        }

        // Initialize to null
        _slaveLanIP = null;
        try {
            String slaveLanIP = PropertyHelper.getProperty(p, "slave.lan.ip", "");
            logger.debug("'slave.lan.ip' has been resolved to {}", slaveLanIP);
            if (slaveLanIP.length() > 0) {
		_slaveLanIP = InetAddress.getByName(slaveLanIP);
            }
        } catch(UnknownHostException e) {
            logger.warn("'slave.lan.ip' is not a valid ip address");
        } catch(Exception e) {
            logger.error("Unknown error occurred trying to get 'slave.lan.ip' config", e);
        }

        _timeout = Integer.parseInt(PropertyHelper.getProperty(p, "slave.timeout", String.valueOf(actualTimeout)));

        _uploadChecksums = p.getProperty("enableuploadchecksums", "true").equals("true");
        _downloadChecksums = p.getProperty("enabledownloadchecksums", "true").equals("true");
        _bufferSize = Integer.parseInt(p.getProperty("bufferSize", "0"));
        _maxPathLength = Integer.parseInt(p.getProperty("maxPathLength", "4096"));

        _concurrentRootIteration = p.getProperty("concurrent.root.iteration", "false").equalsIgnoreCase("true");

        _rootCollectionThreads = 3;
        try {
            _rootCollectionThreads = Integer.parseInt(p.getProperty("rootCollectionThreads", "3"));
        } catch (NumberFormatException e) {
            logger.warn("Unable to read rootCollectionThreads from config, falling back to 3");
        }

        _roots = getDefaultRootBasket();
        loadDiskSelection(p);

        _transfers = new ConcurrentHashMap<TransferIndex, Transfer>();

        try {
            int minport = Integer.parseInt(p.getProperty("slave.portfrom"));
            int maxport = Integer.parseInt(p.getProperty("slave.portto"));
            _portRange = new PortRange(minport, maxport, _bufferSize);
        } catch (NumberFormatException e) {
            logger.warn("Unable to read port range from config, falling back to default random port range " +
                    "specified by the operating system");
            _portRange = new PortRange(_bufferSize);
        }

        _ignorePartialRemerge = p.getProperty("ignore.partialremerge", "false").equalsIgnoreCase("true");
        logger.info("Slave {} connecting to master at {}. Configuration: Conccurrent Root Iteration: {}",
                slaveName, masterIsa, _concurrentRootIteration);

        // Initialize this before we connect a socket
        _central = new SlaveProtocolCentral(this);

        try {
            _socket = (SSLSocket) SSLService.getSSLService().sslSocketFactory(_sslConfig).createSocket();
        } catch (IOException | SSLServiceException e) {
            throw new RuntimeException("Something went wrong connecting to master", e);
        }

        if (getBindIP() != null) {
            try {
                _socket.bind(new InetSocketAddress(getBindIP(), 0));
            } catch (IOException e) {
                throw new IOException("Unable to bind to ["+getBindIP()+":0]", e);
            }
        }

        _socket.setSoTimeout(socketTimeout);
        _socket.connect(masterIsa);
        _socket.setUseClientMode(true);

        logger.debug("[{}] Enabled ciphers for this new connection are as follows: '{}'", _socket.getRemoteSocketAddress(), Arrays.toString(_socket.getEnabledCipherSuites()));
        logger.debug("[{}] Enabled protocols for this new connection are as follows: '{}'", _socket.getRemoteSocketAddress(), Arrays.toString(_socket.getEnabledProtocols()));

        try {
            _socket.startHandshake();
        } catch (SSLHandshakeException e) {
            throw new SSLUnavailableException("Handshake failure, maybe master isn't SSL ready or SSL is disabled.", e);
        }

        _sout = new ObjectOutputStream(new BufferedOutputStream(_socket.getOutputStream()));
        _sout.flush();
        _sin = new ObjectInputStream(new BufferedInputStream(_socket.getInputStream()));

        _sout.writeObject(slaveName);
        _sout.flush();
        _sout.reset();
        startResponseWriter();
    }

    public Properties getConfig() {
        return _cfg;
    }

    public static void main(String... args) {
        try {
            Slave.boot();
        }
        catch (Throwable th) {
            th.printStackTrace();
            logger.fatal("", th);
            System.exit(1);
        }
    }

    public static void boot() throws Exception {
        System.out.println(VERSION + " Slave starting.");
        System.out.println("https://github.com/drftpd-ng/drftpd");
        System.out.println("Further logging will be done using (mostly) log4j, check logs/");
        Thread.currentThread().setName("Slave Main Thread");

        Properties p = ConfigLoader.loadConfig("slave.conf");
        Slave s = new Slave(p);
        try {
            // Register to master
            s.getProtocolCentral().handshakeWithMaster();
            s.sendResponse(new AsyncResponseDiskStatus(s.getDiskStatus()));
            s.setOnline(true);
            s.listenForCommands();
        } catch (IOException e) {
            throw new RuntimeException("Fatal IOException during main boot() process, Slave stopping", e);
        } catch (Exception e) {
            throw new RuntimeException("Fatal Exception during main boot() process, Slave stopping", e);
        } finally {
            s.shutdown();
        }
    }

    private void loadDiskSelection(Properties cfg) {
        String desiredDs = PropertyHelper.getProperty(cfg, "diskselection");
        try {
            Class<?> aClass = Class.forName(desiredDs);
            _diskSelection = (DiskSelectionInterface) aClass.getConstructor(Slave.class).newInstance(this);
        } catch (Exception e) {
            throw new RuntimeException("Cannot create instance of diskselection, check 'diskselection' in the configuration file", e);
        }
    }

    public DiskSelectionInterface getDiskSelection() {
        return _diskSelection;
    }

    private RootCollection getDefaultRootBasket() throws IOException {
        ArrayList<Root> roots = new ArrayList<>();

        for (int i = 1; true; i++) {
            String rootString = _cfg.getProperty("slave.root." + i);

            if (rootString == null) {
                break;
            }

            logger.info("slave.root.{}: {}", i, rootString);
            roots.add(new Root(rootString));
        }

        return new RootCollection(this, roots);
    }

    public void shutdown() {
        logger.warn("Shutdown() called");
        stopCommandExecutors();
        stopResponseWriter();
        if (_sin != null) {
            logger.warn("Closing _sin");
            try {
                _sin.close();
            } catch (IOException ignored) {
            }
            _sin = null;
        }
        if (_sout != null) {
            logger.warn("Closing _sout");
            try {
                _sout.flush();
                _sout.close();
            } catch (IOException ignored) {
            }
            _sout = null;
        }
        if (_socket != null) {
            logger.warn("Closing _socket");
            try {
                _socket.close();
            } catch (IOException ignored) {
            }
            _socket = null;
        }
        setOnline(false);
    }

    public boolean isOnline() {
        return _online;
    }

    public void setOnline(boolean online) {
        logger.info("Setting slave status online to: "+online);
        _online = online;
    }

    public void addTransfer(Transfer transfer) {
        _transfers.put(transfer.getTransferIndex(), transfer);
    }

    public long checkSum(String path) throws IOException {
        return checkSum(_roots.getFile(path));
    }

    public long checkSum(PhysicalFile file) throws IOException {
        logger.debug("Checksumming: {}", file.getPath());

        CRC32 crc32 = new CRC32();
        try (CheckedInputStream in = new CheckedInputStream(new BufferedInputStream(new FileInputStream(file)), crc32)) {
            byte[] buf = new byte[16384];
            while (true) {
                if (in.read(buf) == -1) {
                    break;
                }
            }
            return crc32.getValue();
        }
    }

    public void delete(String path) throws IOException {
        // now deletes files as well as directories, recursive!
        Collection<Root> files;
        try {
            files = _roots.getMultipleRootsForFile(path);
        } catch (FileNotFoundException e) {
            // all is good, it's already gone
            return;
        }

        for (Iterator<Root> iter = files.iterator(); iter.hasNext(); ) {
            Root root = iter.next();
            PhysicalFile file = root.getFile(path);

            if (!file.exists()) {
                iter.remove();
                continue;
                // should never occur
            }

            if (file.isDirectory()) {
                if (!file.deleteRecursive()) {
                    throw new PermissionDeniedException("delete failed on " + path);
                }
                logger.info("DELETEDIR: {}", path);
            } else if (file.isFile()) {
                Path physicalPath = Path.of(file.getPath());
                File dir = physicalPath.toFile();
                logger.info("DELETE: {}, rmfile: {}", path, physicalPath);
                try {
                    Files.delete(physicalPath);
                } catch (NoSuchFileException e) {
                    logger.error("Trying to delete file, but caught NoSuchFileException. BUG?", e);
                } catch (DirectoryNotEmptyException e) {
                    logger.error("Trying to delete file, but caught DirectoryNotEmptyException. BUG?", e);
                } catch (IOException e) {
                    // Sometimes we hit OS cache issues, so lets wait 0.1 seconds to see if that solves it.
                    // If the file exists after this we bail
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ignored) {}

                    if (file.exists()) {
                        logger.error("Delete of {} failed", physicalPath, e);
                        throw new PermissionDeniedException("delete failed on " + path);
                    }
                }

                String[] dirList = dir.list();

                // If the parent directory is empty, then loop to delete it along with empty
                // parents
                while ((dirList != null) && (dirList.length == 0)) {
                    // Stop at the root
                    if (dir.getPath().length() <= root.getPath().length()) {
                        break;
                    }

                    // Get the parent dir
                    java.io.File tmpFile = dir.getParentFile();

                    try {
                        if (Files.deleteIfExists(dir.toPath())) {
                            logger.info("Dir empty, rmdir: {}", dir.getPath());
                        } else {
                            logger.info("dir was empty, but doesn't exist anymore, that is fine {}", dir.getPath());
                        }
                    } catch (DirectoryNotEmptyException dnee) {
                        logger.info("dir was not empty, that is fine, we keep {}", dir.getPath());
                        break;
                    }

                    // If the parent dir doesn't exist, break the loop
                    if (tmpFile == null) {
                        break;
                    }

                    // Rearm the loop on the parent dir
                    dir = new PhysicalFile(tmpFile);
                    dirList = dir.list();
                }
            }
        }
    }

    public void deleteZeroByteFile(String path) throws IOException {
        for (Transfer transfer : getTransfersList()) {
            if (transfer.isReceivingUploadForPath(path)) {
                throw new FileExistsException("Refusing to replace active upload path " + path);
            }
        }

        Collection<Root> roots;
        try {
            roots = new LinkedHashSet<>(_roots.getMultipleRootsForFile(path));
        } catch (FileNotFoundException e) {
            return;
        }

        List<PhysicalFile> zeroByteFiles = new ArrayList<>();
        for (Root root : roots) {
            PhysicalFile file = root.getFile(path);
            if (!file.exists()) {
                continue;
            }
            if (!file.isFile() || file.length() != 0L) {
                throw new FileExistsException("Refusing to replace non-zero upload path " + path
                        + " (size=" + file.length() + ")");
            }
            zeroByteFiles.add(file);
        }

        for (PhysicalFile file : zeroByteFiles) {
            if (file.length() != 0L) {
                throw new FileExistsException("Refusing to replace upload path that is no longer zero bytes "
                        + path + " (size=" + file.length() + ")");
            }
            Files.deleteIfExists(file.toPath());
            logger.info("DELETE ZERO-BYTE UPLOAD: {}", path);
        }
    }

    public int getBufferSize() {
        return _bufferSize;
    }

    public int getMaxPathLength() {
        return _maxPathLength;
    }

    public boolean getDownloadChecksums() {
        return _downloadChecksums;
    }

    public RootCollection getRoots() {
        return _roots;
    }

    public DiskStatus getDiskStatus() {
        return new DiskStatus(_roots.getTotalDiskSpaceAvailable(), _roots.getTotalDiskSpaceCapacity());
    }

    public Transfer getTransfer(TransferIndex index) {
        return _transfers.get(index);
    }

    public boolean getUploadChecksums() {
        return _uploadChecksums;
    }

    private AsyncResponse handleCommand(AsyncCommandArgument ac) {
        return _central.handleCommand(ac);
    }

    private void listenForCommands() throws IOException {
        long lastCommandReceived = System.currentTimeMillis();
        while (true) {
            AsyncCommandArgument ac;

            try {
                Object commandObject = _sin.readObject();
                if (!(commandObject instanceof AsyncCommandArgument)) {
                    String className = commandObject == null ? "null" : commandObject.getClass().getName();
                    throw new IOException("Protocol violation: expected AsyncCommandArgument, received " + className);
                }
                ac = (AsyncCommandArgument) commandObject;

                if (ac == null) {
                    continue;
                }
                lastCommandReceived = System.currentTimeMillis();
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            } catch (EOFException e) {
                throw new RuntimeException("Lost connection to the master, may have been kicked offline", e);
            } catch (SocketTimeoutException e) {
                // if no communication for slave.timeout (_timeout) time, than
                // connection to the master is dead or there is a configuration
                // error
                long millisSinceLastCommand = System.currentTimeMillis() - lastCommandReceived;
                if (_timeout < millisSinceLastCommand) {
                    String message = String.format("Slave is going offline as it hasn't received any communication from the master in %d milliseconds", millisSinceLastCommand);
                    logger.error(message, e);
                    throw new RuntimeException(message, e);
                }
                continue;
            }

            logger.debug("Slave fetched {}", ac);
            dispatchCommand(ac);
        }
    }

    private void dispatchCommand(AsyncCommandArgument command) {
        ExecutorService executor = getCommandExecutor(command.getName());
        try {
            executor.execute(() -> {
                try {
                    sendResponse(handleCommand(command));
                } catch (Throwable e) {
                    sendResponse(new AsyncResponseException(command.getIndex(), e));
                }
            });
        } catch (RejectedExecutionException e) {
            logger.warn("Rejecting {} command because its bounded executor is full", command.getName());
            sendResponse(new AsyncResponseException(command.getIndex(),
                    new IOException("Slave command executor is busy: " + command.getName())));
        }
    }

    private ExecutorService getCommandExecutor(String commandName) {
        return switch (getCommandExecutorType(commandName)) {
            case REMERGE -> _remergeCommandExecutor;
            case TRANSFER -> _transferCommandExecutor;
            case FILESYSTEM -> _filesystemCommandExecutor;
            case CHECKSUM -> _checksumCommandExecutor;
            case CONTROL -> _controlCommandExecutor;
        };
    }

    static CommandExecutorType getCommandExecutorType(String commandName) {
        if ("remerge".equals(commandName)) {
            return CommandExecutorType.REMERGE;
        }
        if ("receive".equals(commandName) || "send".equals(commandName)) {
            return CommandExecutorType.TRANSFER;
        }
        if ("delete".equals(commandName) || "deletezero".equals(commandName)
                || "rename".equals(commandName)) {
            return CommandExecutorType.FILESYSTEM;
        }
        if ("checksum".equals(commandName)) {
            return CommandExecutorType.CHECKSUM;
        }
        return CommandExecutorType.CONTROL;
    }

    private ThreadPoolExecutor createCommandExecutor(String threadName, Properties properties,
                                                       String threadProperty, int defaultThreads,
                                                       String queueProperty, int defaultQueueSize) {
        int threads = getPositiveIntProperty(properties, threadProperty, defaultThreads);
        int queueSize = getPositiveIntProperty(properties, queueProperty, defaultQueueSize);
        AtomicInteger threadSequence = new AtomicInteger();
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, threadName + "-" + threadSequence.incrementAndGet());
            thread.setPriority(Thread.NORM_PRIORITY);
            return thread;
        };
        return new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueSize), threadFactory, new ThreadPoolExecutor.AbortPolicy());
    }

    private int getPositiveIntProperty(Properties properties, String name, int defaultValue) {
        String configuredValue = properties.getProperty(name, Integer.toString(defaultValue));
        try {
            return Math.max(1, Integer.parseInt(configuredValue));
        } catch (NumberFormatException e) {
            logger.warn("Unable to read {} from config, using {}", name, defaultValue);
            return defaultValue;
        }
    }

    private void stopCommandExecutors() {
        stopCommandExecutor(_remergeCommandExecutor);
        stopCommandExecutor(_checksumCommandExecutor);
        stopCommandExecutor(_filesystemCommandExecutor);
        stopCommandExecutor(_transferCommandExecutor);
        stopCommandExecutor(_controlCommandExecutor);
    }

    private void stopCommandExecutor(ThreadPoolExecutor executor) {
        executor.shutdownNow();
        try {
            executor.awaitTermination(1L, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void removeTransfer(Transfer transfer) {
        if (_transfers.remove(transfer.getTransferIndex()) == null) {
            throw new IllegalStateException();
        }
    }

    /**
     * Rename a location on the (slave) local file system from A to B
     * NOTE: master allows destination to exist and expects us to merge
     * @param from The source location we need to rename from
     * @param toDirPath The destination parent path to rename/move too
     * @param toName The destination name under parent to rename/move too
     * @throws IOException if any I/O related issue has arisen throw it
     */
    public void rename(String from, String toDirPath, String toName) throws IOException {
        for (Iterator<Root> rootItems = _roots.iterator(); rootItems.hasNext(); ) {
            Root root = rootItems.next();

            File fromfile = root.getFile(from);

            if (!fromfile.exists()) {
                logger.debug("rename(), from ["+fromfile.getPath()+"] not found in root ["+root.getPath()+"], skipping");
                continue;
            }

            File toDir = root.getFile(toDirPath);
            File tofile = new File(toDir.getPath() + File.separator + toName);

            if (!toDir.exists() && !toDir.mkdirs()) {
                throw new PermissionDeniedException(
                        "renameTo(" + fromfile + ", " + tofile + ") failed to create destination folder");
            }

            // Handle windows case insensitivity
            if (isWindows) {
                // We check full path as we can still move to a different path and case if needed - TODO: verify
                if (fromfile.getPath().equalsIgnoreCase(tofile.getPath())) {
                    logger.debug("rename(), found from ["+fromfile.getPath()+"] to match ["+tofile.getPath()+"]. " +
                            "However we seem to have a case difference, ignoring (on Windows)");
                    continue;
                }
            }

            // Master allows destination to exist, just how should we handle collisions (ignoring for now)
            if (tofile.exists()) {
                logger.error("rename(), tofile ["+tofile.getPath()+"] exists (from: ["+fromfile.getPath()+"]. " +
                        "This used to cause an I/O exception, however master assumes we can merge, " +
                        "so silently not doing anything (for now) - ISSUE");
                continue;
            }

            if (!fromfile.renameTo(tofile)) {
                throw new PermissionDeniedException("renameTo(" + fromfile + ", " + tofile + ") failed");
            }
        }
    }

    public void sendResponse(AsyncResponse response) {
        if (response == null) {
            // handler doesn't return anything or it sends reply on it's own
            // (threaded for example)
            return;
        }

        RuntimeException writerFailure = _responseWriterFailure.get();
        if (writerFailure != null) {
            throw writerFailure;
        }
        if (!_responseWriterRunning) {
            throw new IllegalStateException("Slave response writer is not running");
        }

        int priority = getResponsePriority(response);
        boolean remergeResponse = priority == RESPONSE_PRIORITY_REMERGE;
        if (remergeResponse) {
            reserveRemergeResponseSlot();
            _pendingRemergeResponses.incrementAndGet();
        }

        QueuedResponse queuedResponse = new QueuedResponse(
                response, priority, _responseSequence.getAndIncrement(), remergeResponse);
        try {
            _responseQueue.add(queuedResponse);
        } catch (RuntimeException e) {
            if (remergeResponse) {
                completeRemergeResponse();
            }
            throw e;
        }
    }

    public void awaitRemergeResponsesFlushed() throws IOException, InterruptedException {
        synchronized (_responseWriterMonitor) {
            while (_pendingRemergeResponses.get() > 0) {
                RuntimeException writerFailure = _responseWriterFailure.get();
                if (writerFailure != null) {
                    throw new IOException("Slave response writer failed", writerFailure);
                }
                if (!_responseWriterRunning) {
                    throw new IOException("Slave response writer stopped before remerge responses were flushed");
                }
                _responseWriterMonitor.wait(1000L);
            }
        }
    }

    private void startResponseWriter() {
        _responseWriterFailure.set(null);
        _responseWriterRunning = true;
        _responseWriterThread = new Thread(this::writeQueuedResponses, "Slave Response Writer");
        _responseWriterThread.start();
    }

    private void stopResponseWriter() {
        _responseWriterRunning = false;
        Thread writerThread = _responseWriterThread;
        if (writerThread != null) {
            writerThread.interrupt();
            if (writerThread != Thread.currentThread()) {
                try {
                    writerThread.join(1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        synchronized (_responseWriterMonitor) {
            _responseWriterMonitor.notifyAll();
        }
    }

    private void writeQueuedResponses() {
        try {
            while (_responseWriterRunning) {
                QueuedResponse queuedResponse;
                try {
                    queuedResponse = _responseQueue.poll(1000L, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    if (!_responseWriterRunning) {
                        return;
                    }
                    continue;
                }
                if (queuedResponse == null) {
                    continue;
                }

                try {
                    writeResponse(queuedResponse.response());
                } finally {
                    if (queuedResponse.remergeResponse()) {
                        completeRemergeResponse();
                    }
                }
            }
        } catch (IOException e) {
            RuntimeException failure = new RuntimeException("Unable to write response to master", e);
            _responseWriterFailure.compareAndSet(null, failure);
            logger.error("Slave response writer failed", e);
            _responseWriterRunning = false;
            setOnline(false);
            try {
                if (_socket != null) {
                    _socket.close();
                }
            } catch (IOException ignored) {
            }
        } finally {
            releaseQueuedRemergeResponses();
            synchronized (_responseWriterMonitor) {
                _responseWriterMonitor.notifyAll();
            }
        }
    }

    private void writeResponse(AsyncResponse response) throws IOException {
        _sout.writeObject(response);
        _sout.flush();
        _sout.reset();
        if (!(response instanceof AsyncResponseTransferStatus)) {
            logger.debug("Slave wrote response - {}", response);
        }

        if (response instanceof AsyncResponseException) {
            logger.debug("", ((AsyncResponseException) response).getThrowable());
        }
    }

    private void reserveRemergeResponseSlot() {
        while (_responseWriterRunning) {
            RuntimeException writerFailure = _responseWriterFailure.get();
            if (writerFailure != null) {
                throw writerFailure;
            }
            try {
                if (_remergeResponseSlots.tryAcquire(1L, TimeUnit.SECONDS)) {
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted waiting to queue a remerge response", e);
            }
        }
        throw new IllegalStateException("Slave response writer stopped while queueing remerge data");
    }

    private void completeRemergeResponse() {
        _pendingRemergeResponses.decrementAndGet();
        _remergeResponseSlots.release();
        synchronized (_responseWriterMonitor) {
            _responseWriterMonitor.notifyAll();
        }
    }

    private void releaseQueuedRemergeResponses() {
        QueuedResponse queuedResponse;
        while ((queuedResponse = _responseQueue.poll()) != null) {
            if (queuedResponse.remergeResponse()) {
                completeRemergeResponse();
            }
        }
    }

    static int getResponsePriority(AsyncResponse response) {
        if (response instanceof AsyncResponseRemerge) {
            return RESPONSE_PRIORITY_REMERGE;
        }
        if (response instanceof AsyncResponseDiskStatus) {
            return RESPONSE_PRIORITY_DISK;
        }
        if (response instanceof AsyncResponseTransferStatus) {
            return RESPONSE_PRIORITY_STATUS;
        }
        if (response.getClass() == AsyncResponse.class
                || response instanceof AsyncResponseException
                || response instanceof AsyncResponseTransfer
                || response instanceof AsyncResponseMaxPath
                || response instanceof AsyncResponseSSLCheck) {
            return RESPONSE_PRIORITY_CONTROL;
        }
        return RESPONSE_PRIORITY_STATUS;
    }

    static final class QueuedResponse implements Comparable<QueuedResponse> {
        private final AsyncResponse response;
        private final int priority;
        private final long sequence;
        private final boolean remergeResponse;

        QueuedResponse(AsyncResponse response, int priority, long sequence, boolean remergeResponse) {
            this.response = response;
            this.priority = priority;
            this.sequence = sequence;
            this.remergeResponse = remergeResponse;
        }

        AsyncResponse response() {
            return response;
        }

        boolean remergeResponse() {
            return remergeResponse;
        }

        @Override
        public int compareTo(QueuedResponse other) {
            int priorityComparison = Integer.compare(priority, other.priority);
            return priorityComparison != 0
                    ? priorityComparison
                    : Long.compare(sequence, other.sequence);
        }
    }

    enum CommandExecutorType {
        CONTROL,
        TRANSFER,
        FILESYSTEM,
        CHECKSUM,
        REMERGE
    }

    /**
     * @return The current list of Transfer objects
     */
    public ArrayList<Transfer> getTransfersList() {
        return new ArrayList<>(_transfers.values());
    }

    public String[] getCipherSuites() {
        // returns null if none are configured explicitly
        if (_cipherSuites == null) {
            return null;
        }
        return _cipherSuites;
    }

    public String[] getSSLProtocols() {
        // returns null if none are configured explicitly
        if (_sslProtocols == null) {
            return null;
        }
        return _sslProtocols;
    }

    public Map<TransferIndex, Transfer> getTransferMap() {
        return _transfers;
    }

    public SSLContext getSSLContext() {
        try {
            return SSLGetContext.getSSLContext();
        } catch(GeneralSecurityException | IOException e) {
            return null;
        }
    }

    public PortRange getPortRange() {
        return _portRange;
    }

    public InetAddress getBindIP() {
        return _bindIP;
    }

    public InetAddress getSlaveLanIP() {
        return _slaveLanIP;
    }

    public ObjectInputStream getInputStream() {
        return _sin;
    }

    public ObjectOutputStream getOutputStream() {
        return _sout;
    }

    public SlaveProtocolCentral getProtocolCentral() {
        return _central;
    }

    public boolean ignorePartialRemerge() {
        return _ignorePartialRemerge;
    }

    public int rootCollectionThreads() {
        return _rootCollectionThreads;
    }

    public boolean concurrentRootIteration() {
        return _concurrentRootIteration;
    }

    protected String getSettingAsString(String key) throws Exception {
        logger.debug("Looking up key: {} as String", key);
        return getConfig().getProperty(key);
    }

    protected char[] getSecureSetting(String key) throws Exception {
        logger.debug("!!NOT IMPLEMENTED!! Looking up key: {} as char[] - !!NOT IMPLEMENTED!!", key);
        return null;
    }

    protected List<String> getSettingAsList(String key) throws Exception {
        logger.debug("Looking up key: {} as List<String>", key);
        List<String> data = PropertyHelper.getStringListedProperty(getConfig(), key);
        if (data == null) {
            return null;
        }
        logger.debug("Got List<String> for {} as -> [{}]", key, Arrays.toString(data.toArray()));
        return data;
    }
}
