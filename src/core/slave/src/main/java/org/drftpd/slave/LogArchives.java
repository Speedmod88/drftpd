package org.drftpd.slave;

import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongConsumer;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/** Bounded, read-only archive previews shared by local and remote WebAdmin logs. */
public final class LogArchives implements AutoCloseable {
    public static final int MAX_OUTPUT_BYTES = 262144;
    public static final long MAX_SCAN_BYTES = 512L * 1024 * 1024;
    private static final long TTL = 5 * 60000L;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,159}\\.log(?:\\.gz)?");
    private static final Pattern DATE = Pattern.compile(".*-([0-9]{4}-[0-9]{2}-[0-9]{2})\\.log(?:\\.gz)?$");
    private final Map<String, Task> tasks = new LinkedHashMap<>();
    private final ThreadPoolExecutor worker;

    public LogArchives(String name) {
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(4), runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true); thread.setPriority(Thread.MIN_PRIORITY); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    public record Preview(String job, String file, String state, String content, String error,
                          long scannedBytes, boolean limited) { }
    private static final class Task {
        final String id = UUID.randomUUID().toString();
        final String owner, file;
        final Path path;
        final int lines;
        final long size, modified;
        volatile String state = "queued", content = "", error = "";
        volatile long scanned, finished;
        volatile boolean limited;
        Task(String owner, String file, Path path, int lines) throws IOException {
            this.owner = owner; this.file = file; this.path = path; this.lines = lines;
            size = Files.size(path); modified = Files.getLastModifiedTime(path).toMillis();
        }
        Preview view() {
            String current = state;
            return new Preview(id, file, current, content, error, scanned, limited);
        }
    }

    public synchronized Preview start(String owner, Path root, String relative, int lines) throws IOException {
        if (!validPath(relative) || !relative.endsWith(".log.gz") || lines < 1 || lines > 10000)
            throw new IOException("Invalid compressed log preview request");
        Path path = safeFile(root, relative);
        long now = System.currentTimeMillis();
        tasks.values().removeIf(task -> task.finished != 0 && now - task.finished > TTL);
        for (Task task : tasks.values()) {
            if (task.owner.equals(owner) && task.path.equals(path) && task.lines == lines
                    && task.size == Files.size(path) && task.modified == Files.getLastModifiedTime(path).toMillis()
                    && !task.state.equals("failed")) return task.view();
        }
        while (tasks.size() >= 8) {
            String expired = tasks.values().stream().filter(task -> task.finished != 0).map(task -> task.id).findFirst().orElse(null);
            if (expired == null) throw new IOException("Archive reader busy; retry shortly");
            tasks.remove(expired);
        }
        Task task = new Task(owner, relative, path, lines);
        tasks.put(task.id, task);
        try { worker.execute(() -> run(task)); }
        catch (RejectedExecutionException e) { tasks.remove(task.id); throw new IOException("Archive reader busy; retry shortly", e); }
        return task.view();
    }

    public synchronized Preview status(String owner, String id) throws IOException {
        Task task = tasks.get(id);
        if (task != null && task.finished != 0 && System.currentTimeMillis() - task.finished > TTL) {
            tasks.remove(id);
            task = null;
        }
        if (task == null || !task.owner.equals(owner)) throw new IOException("Archive preview expired or unavailable");
        return task.view();
    }

    private void run(Task task) {
        task.state = "running";
        try {
            task.content = read(task.path, task.lines, MAX_SCAN_BYTES, 30000, bytes -> task.scanned = bytes);
            if (Files.size(task.path) != task.size || Files.getLastModifiedTime(task.path).toMillis() != task.modified)
                throw new IOException("Archive changed during reading; refresh and retry");
            task.limited = task.scanned > MAX_OUTPUT_BYTES;
            task.state = "completed";
        } catch (Exception e) {
            task.content = "";
            task.error = e.getMessage() == null ? "Archive read failed" : e.getMessage();
            task.state = "failed";
        } finally { task.finished = System.currentTimeMillis(); }
    }

    static String read(Path file, int lines, long maximumBytes, long maximumMillis, LongConsumer progress) throws IOException {
        byte[] ring = new byte[MAX_OUTPUT_BYTES], input = new byte[16384];
        int position = 0, retained = 0;
        long total = 0, deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maximumMillis);
        try (var raw = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS);
             var gzip = new GZIPInputStream(raw, input.length)) {
            int count;
            while ((count = gzip.read(input)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Archive read cancelled");
                if (System.nanoTime() > deadline) throw new IOException("Archive preview exceeded 30-second read limit");
                total += count;
                if (total > maximumBytes) throw new IOException("Archive exceeds preview scan limit (512 MiB expanded); inspect it outside WebAdmin");
                int first = Math.min(count, ring.length - position);
                System.arraycopy(input, 0, ring, position, first);
                if (first < count) System.arraycopy(input, first, ring, 0, count - first);
                position = (position + count) % ring.length;
                retained = Math.min(ring.length, retained + count);
                progress.accept(total);
            }
        }
        byte[] bytes = new byte[retained];
        int start = retained == ring.length ? position : 0;
        int first = Math.min(retained, ring.length - start);
        System.arraycopy(ring, start, bytes, 0, first);
        if (first < retained) System.arraycopy(ring, 0, bytes, first, retained - first);
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (total > retained) {
            int newline = text.indexOf('\n');
            if (newline >= 0) text = text.substring(newline + 1);
        }
        String[] all = text.split("\\R", -1);
        int end = all.length;
        if (end > 0 && all[end - 1].isEmpty()) end--;
        return String.join("\n", Arrays.copyOfRange(all, Math.max(0, end - lines), end));
    }

    public static boolean validPath(String relative) {
        if (relative == null) return false;
        String[] parts = relative.split("/", -1);
        return (parts.length == 1 || parts.length == 2 && parts[0].matches("[0-9]{4}-[0-9]{2}"))
                && NAME.matcher(parts[parts.length - 1]).matches();
    }

    public static String date(String relative) {
        var match = DATE.matcher(relative);
        if (!match.matches()) return "";
        try { return LocalDate.parse(match.group(1)).toString(); }
        catch (java.time.DateTimeException e) { return ""; }
    }

    public static boolean archived(String relative) {
        return relative.endsWith(".gz") || relative.contains("/") || !date(relative).isEmpty();
    }

    public static Path safeFile(Path root, String relative) throws IOException {
        if (!validPath(relative)) throw new IOException("Invalid log filename");
        root = root.toAbsolutePath().normalize();
        Path file = root.resolve(relative).normalize();
        for (Path current = root; current != null; current = current.getParent())
            if (Files.isSymbolicLink(current)) throw new IOException("Symbolic log root refused");
        Path current = root;
        for (Path part : root.relativize(file)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IOException("Symbolic log path refused");
        }
        if (!file.startsWith(root) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Log file unavailable");
        return file;
    }

    public static List<String> list(Path root) throws IOException {
        List<String> files;
        try (var stream = Files.walk(root, 2)) {
            files = stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .map(path -> root.relativize(path).toString().replace('\\', '/')).filter(LogArchives::validPath)
                    .limit(5001).sorted().toList();
        }
        if (files.size() > 5000) throw new IOException("More than 5000 log files; reduce the retained log directory");
        return files;
    }

    @Override public void close() { worker.shutdownNow(); synchronized (this) { tasks.clear(); } }
}
