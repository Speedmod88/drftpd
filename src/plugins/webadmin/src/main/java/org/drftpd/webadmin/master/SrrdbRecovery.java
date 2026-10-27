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
        default LocalFile compare(String username, String releasePath, String relativeFile) throws Exception {
            return new LocalFile(!missing(username, releasePath, relativeFile), "", -1, "");
        }
        List<String> releases(String username, String path, boolean recursive, int limit) throws Exception;
        boolean missing(String username, String releasePath, String relativeFile) throws Exception;
        String install(String username, String releasePath, SrrdbClient.RemoteFile file, byte[] content) throws Exception;
        default String replace(String username, String releasePath, SrrdbClient.RemoteFile file,
                               byte[] content, LocalFile original, String transaction) throws Exception {
            throw new IOException("Safe metadata replacement is not supported");
        }
        @Override default void close() { }
    }
    static final int MAX_ENTRIES = 500;
    private static final Logger logger = LogManager.getLogger("org.drftpd.webadmin.srrdb");
    record LocalFile(boolean exists, String name, long size, String crc) { }
    record Comparison(String releasePath, String file, LocalFile local, long size, String crc, String status, String source) { }
    private final List<Comparison> comparisons = new ArrayList<>();

    static String comparisonStatus(LocalFile local, SrrdbClient.RemoteFile remote) {
        if (!local.exists()) return "MISSING";
        if (local.size() >= 0 && local.size() != remote.size()) return "MISMATCH";
        if (local.size() < 0 || local.crc().isEmpty()) return "PRESENT - CRC UNKNOWN";
        return local.size() == remote.size() && local.crc().equalsIgnoreCase(remote.crc()) ? "MATCH" : "MISMATCH";
    }
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
    private int notFound;
    private volatile boolean cancelled;
    private volatile boolean closed;
    private String scanId;
    private String scanOwner;
    private long scanCreated;
    private long scanFinished;
    private String savedVersion;
    interface HistorySink { void save(String username, Map<String, Object> data) throws IOException; }
    private HistorySink historySink;

    synchronized void setHistorySink(HistorySink sink) { historySink = sink; }

    synchronized Map<String, Object> historyView() {
        if (scanId == null) return Map.of();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", scanId);
        data.put("command", "SRRDB scan");
        data.put("created", scanCreated);
        data.put("state", scanState);
        data.put("finished", scanFinished);
        data.put("recovery", view());
        data.put("owner", scanOwner);
        return data;
    }

    synchronized void checkpointHistory() {
        if (scanId == null) return;
        String version = scanId + ":" + scanState + ":" + scanned + ":" + comparisons.size() + ":" + errors + ":" + scanFinished
                + ":" + gson.toJson(entries.values());
        if (version.equals(savedVersion)) return;
        Map<String, Object> data = historyView();
        try {
            Path target = stateFile.resolveSibling(stateFile.getFileName() + ".scan.json");
            Files.createDirectories(target.getParent());
            Path temp = Files.createTempFile(target.getParent(), "srrdb-scan-", ".tmp");
            try {
                Files.writeString(temp, gson.toJson(data), StandardCharsets.UTF_8);
                try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException e) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
            } finally { Files.deleteIfExists(temp); }
            if (historySink != null) historySink.save(scanOwner, data);
            savedVersion = version;
        } catch (IOException e) { logger.error("Unable to persist srrDB scan results", e); }
    }

    private void loadScan() {
        Path path = stateFile.resolveSibling(stateFile.getFileName() + ".scan.json");
        if (!Files.exists(path)) return;
        try {
            if (Files.isSymbolicLink(path) || Files.size(path) > 16 * 1024 * 1024) throw new IOException("Invalid scan snapshot");
            var data = gson.fromJson(Files.readString(path), com.google.gson.JsonObject.class);
            var result = data.getAsJsonObject("recovery");
            String restoredId = UUID.fromString(data.get("id").getAsString()).toString();
            scanOwner = data.get("owner").getAsString();
            scanCreated = data.get("created").getAsLong();
            scanFinished = data.has("finished") ? data.get("finished").getAsLong() : 0;
            scanState = result.get("scanState").getAsString();
            if (scanState.equals("running") || scanState.equals("queued")) scanState = "interrupted";
            scanMessage = scanState.equals("interrupted") ? "Interrupted by restart; not automatically resumed." : result.get("scanMessage").getAsString();
            scanned = result.get("scanned").getAsInt();
            total = result.get("total").getAsInt();
            errors = result.get("errors").getAsInt();
            notFound = result.get("notFound").getAsInt();
            for (var item : result.getAsJsonArray("comparisons")) {
                if (comparisons.size() >= 2000) break;
                comparisons.add(gson.fromJson(item, Comparison.class));
            }
            for (var item : result.getAsJsonArray("scanErrors")) if (scanErrors.size() < 20) scanErrors.add(item.getAsString());
            scanId = restoredId;
        } catch (IOException | RuntimeException e) { logger.error("Cannot restore srrDB scan results; snapshot preserved", e); }
    }

    SrrdbRecovery(Path stateFile, int scanLimit, SrrdbClient client, Library library) throws IOException {
        this.stateFile = stateFile;
        this.scanLimit = scanLimit;
        this.client = client;
        this.library = library;
        load();
        loadScan();
    }

    synchronized Map<String, Object> view() {
        List<Map<String, Object>> files = new ArrayList<>();
        for (Entry entry : entries.values()) {
            files.add(Map.ofEntries(Map.entry("id", entry.id), Map.entry("releasePath", entry.releasePath),
                    Map.entry("file", entry.file), Map.entry("size", entry.size), Map.entry("crc", entry.crc),
                    Map.entry("state", entry.state), Map.entry("message", entry.message),
                    Map.entry("replacement", entry.replacement),
                    Map.entry("original", entry.original == null ? new LocalFile(false, "", -1, "") : entry.original),
                    Map.entry("requestedBy", entry.requestedBy), Map.entry("decidedBy", entry.decidedBy),
                    Map.entry("source", SrrdbClient.downloadUri(releaseName(entry.releasePath), entry.file).toString())));
        }
        return Map.of("files", files, "scanState", scanState, "scanMessage", scanMessage,
                "scanned", scanned, "total", total, "errors", errors, "scanLimit", scanLimit,
                "scanErrors", new ArrayList<>(scanErrors), "notFound", notFound,
                "comparisons", new ArrayList<>(comparisons));
    }

    synchronized void scan(String username, String path, boolean recursive) {
        String normalizedPath = normalizePath(path);
        if (closed) throw new IllegalArgumentException("Recovery service is stopping");
        if (scanState.equals("running") || scanState.equals("queued")) throw new IllegalArgumentException("A scan is already running");
        checkpointHistory();
        scanId = UUID.randomUUID().toString();
        scanOwner = username;
        scanCreated = System.currentTimeMillis();
        scanFinished = 0;
        cancelled = false;
        scanned = 0;
        total = 0;
        errors = 0;
        notFound = 0;
        scanErrors.clear();
        comparisons.clear();
        scanMessage = "";
        worker.execute(() -> scanNow(username, normalizedPath, recursive));
        scanState = "queued";
    }

    private void scanNow(String username, String path, boolean recursive) {
        synchronized (this) { scanState = "running"; }
        logger.info("srrDB scan started: user={} path={} recursive={} limit={}", username, path, recursive, scanLimit);
        try {
            List<String> paths = library.releases(username, path, recursive, scanLimit);
            synchronized (this) { total = paths.size(); }
            for (String returnedPath : paths) {
                String releasePath = normalizePath(returnedPath);
                if (cancelled || closed || Thread.currentThread().isInterrupted()) break;
                synchronized (this) { scanMessage = releasePath; }
                try {
                    for (SrrdbClient.RemoteFile file : client.details(releaseName(releasePath))) {
                        if (cancelled || closed || Thread.currentThread().isInterrupted()) break;
                        LocalFile local = library.compare(username, releasePath, file.name());
                        String status = comparisonStatus(local, file);
                        String source = SrrdbClient.downloadUri(releaseName(releasePath), file.name()).toString();
                        synchronized (this) {
                            if (comparisons.size() < 2000)
                                comparisons.add(new Comparison(releasePath, file.name(), local, file.size(), file.crc(), status, source));
                        }
                        logger.info("srrDB comparison: path={} file={} local={} remoteSize={} remoteCRC={} status={} source={}",
                                releasePath, file.name(), local, file.size(), file.crc(), status, source);
                        boolean replacement = status.equals("MISMATCH") && !local.crc().isEmpty();
                        if (!replacement && !library.missing(username, releasePath, file.name())) continue;
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
                            entry.replacement = replacement;
                            entry.original = replacement ? local : null;
                            entries.put(entry.id, entry);
                            save();
                        }
                    }
                } catch (SrrdbClient.ReleaseNotFoundException e) {
                    synchronized (this) { notFound++; }
                    logger.info("srrDB release not found: path={} release={}", releasePath, releaseName(releasePath));
                } catch (Exception e) {
                    synchronized (this) {
                        errors++;
                        if (scanErrors.size() < 20) scanErrors.add(releasePath + ": " + e.getMessage());
                    }
                    logger.warn("srrDB lookup failed: path={} reason={}", releasePath, e.toString());
                    logger.debug("srrDB lookup failure details: path={}", releasePath, e);
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
        logger.info("srrDB scan ended: user={} state={} scanned={} notFound={} errors={}",
                username, scanState, scanned, notFound, errors);
        synchronized (this) { scanFinished = System.currentTimeMillis(); }
        checkpointHistory();
    }

    synchronized void cancel() { cancelled = true; }

    synchronized void decide(String id, String action, String username) throws IOException {
        if (closed) throw new IllegalArgumentException("Recovery service is stopping");
        Entry entry = entries.get(id);
        if (entry == null) throw new IllegalArgumentException("Review file not found");
        if (!entry.state.equals("pending") && !entry.state.equals("failed")) throw new IllegalArgumentException("File is already decided or processing");
        if (!action.equals("reject") && !action.equals(entry.replacement ? "replace" : "accept"))
            throw new IllegalArgumentException("Use explicit replacement approval for existing metadata");
        String previous = entry.state;
        entry.decidedBy = username;
        entry.state = action.equals("reject") ? "rejected" : "queued";
        entry.message = "";
        try {
            save();
            if (!action.equals("reject")) worker.execute(this::installNext);
        } catch (IOException | RuntimeException e) {
            entry.state = previous;
            try { save(); } catch (IOException suppressed) { e.addSuppressed(suppressed); }
            throw e;
        }
        logger.info("srrDB decision: user={} action={} target={}/{}", username, action, entry.releasePath, entry.file);
        checkpointHistory();
    }

    private void installNext() {
        Entry next;
        synchronized (this) {
            next = entries.values().stream().filter(item -> item.state.equals("queued"))
                    .min(java.util.Comparator.comparing(item -> item.replacement)).orElse(null);
            if (next == null) return;
            next.state = "downloading";
        }
        install(next, next.decidedBy);
    }

    private void install(Entry entry, String username) {
        try {
            synchronized (this) { entry.state = "downloading"; save(); }
            if (closed || (!entry.replacement && !library.missing(username, entry.releasePath, entry.file)))
                throw new IOException("Target unavailable or no longer missing; nothing was overwritten");
            SrrdbClient.RemoteFile file = new SrrdbClient.RemoteFile(entry.file, entry.size, entry.crc);
            byte[] content = client.download(releaseName(entry.releasePath), file);
            if (closed || Thread.currentThread().isInterrupted()) throw new IOException("Recovery cancelled");
            String slave = entry.replacement
                    ? library.replace(username, entry.releasePath, file, content, entry.original, entry.id)
                    : library.install(username, entry.releasePath, file, content);
            synchronized (this) { entry.state = "installed"; entry.message = entry.replacement
                    ? "Verified and replaced on " + slave + "; private backup transaction " + entry.id
                    : "Verified and imported on " + slave; }
            logger.info("srrDB import completed: user={} slave={} path={}/{} size={} crc={}",
                    username, slave, entry.releasePath, entry.file, entry.size, entry.crc);
        } catch (Exception e) {
            synchronized (this) { entry.state = "failed"; entry.message = String.valueOf(e.getMessage()); }
            logger.warn("srrDB import failed: user={} path={}/{}", username, entry.releasePath, entry.file, e);
        } finally {
            synchronized (this) {
                try { save(); } catch (IOException e) { logger.error("Unable to persist srrDB review", e); }
            }
            checkpointHistory();
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
                entry.releasePath = normalizePath(entry.releasePath);
                if (!SrrdbClient.metadataPath(entry.file) || entry.id == null || entry.crc == null
                        || !entry.crc.matches("(?i)[0-9a-f]{8}")) throw new IOException("Invalid srrDB review entry");
                UUID.fromString(entry.id);
                if (entry.replacement && (entry.original == null || entry.original.crc() == null
                        || !entry.original.crc().matches("(?i)[0-9a-f]{8}")))
                    throw new IOException("Invalid replacement approval snapshot");
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
    static String normalizePath(String path) {
        validatePath(path);
        String normalized = path.replaceAll("/+", "/");
        return normalized.length() > 1 && normalized.endsWith("/")
                ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    static String releaseName(String path) {
        String normalized = normalizePath(path);
        if (normalized.equals("/")) throw new IllegalArgumentException("Select a release directory");
        return normalized.substring(normalized.lastIndexOf('/') + 1);
    }

    @Override public void close() {
        checkpointHistory();
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
        boolean replacement;
        LocalFile original;
        Entry(String path, SrrdbClient.RemoteFile file, String username) {
            this.releasePath = path;
            this.file = file.name();
            this.size = file.size();
            this.crc = file.crc();
            this.requestedBy = username;
        }
    }
}
