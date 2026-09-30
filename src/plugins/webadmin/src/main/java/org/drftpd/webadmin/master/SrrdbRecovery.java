package org.drftpd.webadmin.master;

import com.google.gson.Gson;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Bounded background review workflow, separated from HTTP for later plugin reuse. */
final class SrrdbRecovery implements AutoCloseable {
    interface Library extends AutoCloseable {
        List<String> releases(String username, String path, boolean recursive, int limit) throws Exception;
        boolean missing(String username, String releasePath, String relativeFile) throws Exception;
        String install(String username, String releasePath, SrrdbClient.RemoteFile file, byte[] content) throws Exception;
        @Override default void close() { }
    }
    static final int MAX_ENTRIES = 500;
    private static final Logger logger = LogManager.getLogger(SrrdbRecovery.class);
    private final Gson gson = new Gson();
    private final Path stateFile;
    private final int scanLimit;
    private final SrrdbClient client;
    private final Library library;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final List<String> scanErrors = new ArrayList<>();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), runnable -> {
                Thread thread = new Thread(runnable, "WebAdmin-srrDB");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private String scanState = "idle";
    private String scanMessage = "";
    private int scanned;
    private int total;
    private int errors;
    private volatile boolean cancelled;
    private volatile boolean closed;

    SrrdbRecovery(Path stateFile, int scanLimit, SrrdbClient client, Library library) throws IOException {
        this.stateFile = stateFile;
        this.scanLimit = scanLimit;
        this.client = client;
        this.library = library;
        load();
    }

    synchronized Map<String, Object> view() {
        List<Map<String, Object>> files = new ArrayList<>();
        for (Entry entry : entries.values()) {
            files.add(Map.ofEntries(Map.entry("id", entry.id), Map.entry("releasePath", entry.releasePath),
                    Map.entry("file", entry.file), Map.entry("size", entry.size), Map.entry("crc", entry.crc),
                    Map.entry("state", entry.state), Map.entry("message", entry.message),
                    Map.entry("requestedBy", entry.requestedBy), Map.entry("decidedBy", entry.decidedBy),
                    Map.entry("source", SrrdbClient.downloadUri(releaseName(entry.releasePath), entry.file).toString())));
        }
        return Map.of("files", files, "scanState", scanState, "scanMessage", scanMessage,
                "scanned", scanned, "total", total, "errors", errors, "scanLimit", scanLimit,
                "scanErrors", new ArrayList<>(scanErrors));
    }

    synchronized void scan(String username, String path, boolean recursive) {
        validatePath(path);
        if (closed) throw new IllegalArgumentException("Recovery service is stopping");
        if (scanState.equals("running") || scanState.equals("queued")) throw new IllegalArgumentException("A scan is already running");
        cancelled = false;
        scanned = 0;
        total = 0;
        errors = 0;
        scanErrors.clear();
        scanMessage = "";
        worker.execute(() -> scanNow(username, path, recursive));
        scanState = "queued";
    }

    private void scanNow(String username, String path, boolean recursive) {
        synchronized (this) { scanState = "running"; }
        logger.info("srrDB scan started: user={} path={} recursive={} limit={}", username, path, recursive, scanLimit);
        try {
            List<String> paths = library.releases(username, path, recursive, scanLimit);
            synchronized (this) { total = paths.size(); }
            for (String releasePath : paths) {
                if (cancelled || closed || Thread.currentThread().isInterrupted()) break;
                synchronized (this) { scanMessage = releasePath; }
                try {
                    for (SrrdbClient.RemoteFile file : client.details(releaseName(releasePath))) {
                        if (cancelled || closed || Thread.currentThread().isInterrupted()) break;
                        if (!library.missing(username, releasePath, file.name())) continue;
                        synchronized (this) {
                            boolean duplicate = entries.values().stream().anyMatch(item ->
                                    item.releasePath.equals(releasePath) && item.file.equalsIgnoreCase(file.name()));
                            if (duplicate) continue;
                            if (entries.size() >= MAX_ENTRIES) {
                                cancelled = true;
                                scanMessage = "Review list full (500 files). Clear finished entries before scanning again.";
                                break;
                            }
                            Entry entry = new Entry(releasePath, file, username);
                            entries.put(entry.id, entry);
                            save();
                        }
                    }
                } catch (Exception e) {
                    synchronized (this) {
                        errors++;
                        if (scanErrors.size() < 20) scanErrors.add(releasePath + ": " + e.getMessage());
                    }
                    logger.warn("srrDB lookup failed: path={} reason={}", releasePath, e.toString());
                }
                synchronized (this) { scanned++; }
            }
            synchronized (this) {
                scanState = cancelled || closed || Thread.currentThread().isInterrupted() ? "cancelled" : "completed";
                if (!cancelled) scanMessage = total >= scanLimit && recursive
                        ? "Scan limit reached; narrow the path for additional releases." : "Scan finished";
            }
        } catch (Exception e) {
            synchronized (this) { scanState = "failed"; scanMessage = String.valueOf(e.getMessage()); }
            logger.warn("srrDB scan failed: user={} path={}", username, path, e);
        }
        logger.info("srrDB scan ended: user={} state={} scanned={} errors={}", username, scanState, scanned, errors);
    }

    synchronized void cancel() { cancelled = true; }

    synchronized void decide(String id, String action, String username) throws IOException {
        if (closed) throw new IllegalArgumentException("Recovery service is stopping");
        Entry entry = entries.get(id);
        if (entry == null) throw new IllegalArgumentException("Review file not found");
        if (!entry.state.equals("pending") && !entry.state.equals("failed")) throw new IllegalArgumentException("File is already decided or processing");
        if (!action.equals("accept") && !action.equals("reject")) throw new IllegalArgumentException("Invalid decision");
        String previous = entry.state;
        entry.decidedBy = username;
        entry.state = action.equals("accept") ? "queued" : "rejected";
        entry.message = "";
        try {
            save();
            if (action.equals("accept")) worker.execute(() -> install(entry, username));
        } catch (IOException | RuntimeException e) {
            entry.state = previous;
            try { save(); } catch (IOException suppressed) { e.addSuppressed(suppressed); }
            throw e;
        }
        logger.info("srrDB decision: user={} action={} target={}/{}", username, action, entry.releasePath, entry.file);
    }

    private void install(Entry entry, String username) {
        try {
            synchronized (this) { entry.state = "downloading"; save(); }
            if (closed || !library.missing(username, entry.releasePath, entry.file)) throw new IOException("Target unavailable or no longer missing; nothing was overwritten");
            SrrdbClient.RemoteFile file = new SrrdbClient.RemoteFile(entry.file, entry.size, entry.crc);
            byte[] content = client.download(releaseName(entry.releasePath), file);
            if (closed || Thread.currentThread().isInterrupted()) throw new IOException("Recovery cancelled");
            String slave = library.install(username, entry.releasePath, file, content);
            synchronized (this) { entry.state = "installed"; entry.message = "Verified and imported on " + slave; }
            logger.info("srrDB import completed: user={} slave={} path={}/{} size={} crc={}",
                    username, slave, entry.releasePath, entry.file, entry.size, entry.crc);
        } catch (Exception e) {
            synchronized (this) { entry.state = "failed"; entry.message = String.valueOf(e.getMessage()); }
            logger.warn("srrDB import failed: user={} path={}/{}", username, entry.releasePath, entry.file, e);
        } finally {
            synchronized (this) {
                try { save(); } catch (IOException e) { logger.error("Unable to persist srrDB review", e); }
            }
        }
    }

    synchronized void clearFinished() throws IOException {
        entries.values().removeIf(entry -> entry.state.equals("installed") || entry.state.equals("rejected"));
        save();
    }

    private void load() throws IOException {
        if (!Files.exists(stateFile)) return;
        if (Files.isSymbolicLink(stateFile) || Files.size(stateFile) > 4 * 1024 * 1024) throw new IOException("Invalid srrDB state file");
        try {
            Entry[] saved = gson.fromJson(Files.readString(stateFile), Entry[].class);
            if (saved == null || saved.length > MAX_ENTRIES) throw new IOException("Invalid srrDB review state");
            for (Entry entry : saved) {
                validatePath(entry.releasePath);
                if (!SrrdbClient.metadataPath(entry.file) || entry.id == null || entry.crc == null
                        || !entry.crc.matches("(?i)[0-9a-f]{8}")) throw new IOException("Invalid srrDB review entry");
                if (entry.state.equals("queued") || entry.state.equals("downloading")) {
                    entry.state = "failed";
                    entry.message = "Interrupted by restart. Check the destination before approving again.";
                }
                entries.put(entry.id, entry);
            }
        } catch (RuntimeException e) { throw new IOException("Unable to load srrDB review state; original file preserved", e); }
    }

    private void save() throws IOException {
        if (closed) return;
        Files.createDirectories(stateFile.getParent());
        Path temp = Files.createTempFile(stateFile.getParent(), "srrdb-", ".tmp");
        try {
            Files.writeString(temp, gson.toJson(entries.values()), StandardCharsets.UTF_8);
            try { Files.move(temp, stateFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, stateFile, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }

    static void validatePath(String path) {
        if (path == null || !path.startsWith("/") || path.length() > 2048 || path.indexOf(92) >= 0
                || path.contains(":") || path.chars().anyMatch(c -> c < 32 || c == 127)) throw new IllegalArgumentException("An absolute VFS path is required");
        for (String part : path.split("/")) if (part.equals("..") || part.equals(".")) throw new IllegalArgumentException("Invalid VFS path");
    }
    static String releaseName(String path) { return path.substring(path.lastIndexOf('/') + 1); }

    @Override public void close() {
        synchronized (this) { closed = true; cancelled = true; }
        worker.shutdownNow();
        library.close();
    }

    private static final class Entry {
        String id = UUID.randomUUID().toString();
        String releasePath;
        String file;
        long size;
        String crc;
        String state = "pending";
        String message = "";
        String requestedBy;
        String decidedBy = "";
        Entry(String path, SrrdbClient.RemoteFile file, String username) {
            this.releasePath = path;
            this.file = file.name();
            this.size = file.size();
            this.crc = file.crc();
            this.requestedBy = username;
        }
    }
}
