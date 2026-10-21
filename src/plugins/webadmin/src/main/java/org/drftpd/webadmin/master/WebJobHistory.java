package org.drftpd.webadmin.master;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Atomic, per-account result snapshots; stored operations are never replayed. */
final class WebJobHistory {
    private static final int MAX_BYTES = 32 * 1024 * 1024;
    private final Path root;
    private final Gson gson = new Gson();
    private final Map<String, Long> lastPruned = new HashMap<>();
    WebJobHistory(Path root) { this.root = root; }

    private Path account(String username) throws IOException {
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(username.getBytes(StandardCharsets.UTF_8)));
            Path directory = root.resolve(hash);
            if (Files.isSymbolicLink(root) || Files.isSymbolicLink(directory)) throw new IOException("Invalid history directory");
            Files.createDirectories(directory);
            return directory;
        } catch (java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
    }

    synchronized void save(String username, Map<String, Object> data) throws IOException {
        String id = UUID.fromString(String.valueOf(data.get("id"))).toString();
        Path directory = account(username);
        byte[] bytes = gson.toJson(data).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("Job history exceeds 32 MiB");
        atomic(directory.resolve(id + ".json"), bytes);
        Map<String, Object> summary = new LinkedHashMap<>();
        for (String key : List.of("id", "command", "created", "state", "finished")) summary.put(key, data.get(key));
        atomic(directory.resolve(id + ".meta"), gson.toJson(summary).getBytes(StandardCharsets.UTF_8));
        long now = System.currentTimeMillis();
        if (now - lastPruned.getOrDefault(username, 0L) > TimeUnit.MINUTES.toMillis(1)) {
            list(username);
            lastPruned.put(username, now);
        }
    }

    private void atomic(Path target, byte[] content) throws IOException {
        Path temp = Files.createTempFile(target.getParent(), "history-", ".tmp");
        try {
            Files.write(temp, content);
            try { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }

    synchronized JsonObject read(String username, String id) throws IOException {
        String safe = UUID.fromString(id).toString();
        return readFile(account(username).resolve(safe + ".json"), MAX_BYTES);
    }

    synchronized List<JsonObject> list(String username) throws IOException {
        List<JsonObject> result = new ArrayList<>();
        Path directory = account(username);
        long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30);
        try (var files = Files.list(directory)) {
            var summaries = files.filter(p -> p.toString().endsWith(".meta"))
                    .sorted(Comparator.comparingLong(this::modified).reversed()).toList();
            for (int i = 0; i < summaries.size(); i++) {
                Path file = summaries.get(i);
                try {
                    String id = UUID.fromString(file.getFileName().toString().replace(".meta", "")).toString();
                    if (i >= 1000 || modified(file) < cutoff) {
                        Files.deleteIfExists(directory.resolve(id + ".json"));
                        Files.deleteIfExists(file);
                    } else result.add(readFile(file, 16384));
                } catch (IOException | IllegalArgumentException ignored) { }
            }
        }
        result.sort(Comparator.comparingLong((JsonObject item) -> item.get("created").getAsLong()).reversed());
        return result;
    }

    private JsonObject readFile(Path file, int limit) throws IOException {
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file) || Files.size(file) > limit)
            throw new IOException("History result unavailable");
        try {
            JsonObject value = gson.fromJson(Files.readString(file), JsonObject.class);
            if (value == null || !value.has("id") || !value.has("created")) throw new IOException("Invalid history result");
            UUID.fromString(value.get("id").getAsString());
            value.get("created").getAsLong();
            if (!value.has("state") || !value.has("command")) throw new IOException("Incomplete history result");
            return value;
        } catch (RuntimeException e) { throw new IOException("Invalid history result", e); }
    }

    private long modified(Path file) {
        try { return Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toMillis(); }
        catch (IOException e) { return 0; }
    }
}
