package org.drftpd.webadmin.master;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;

/** Exact release lookups only; remote names and file contents are untrusted. */
final class SrrdbClient {
    interface Transport { byte[] get(URI uri, int maximumBytes) throws IOException; }
    record RemoteFile(String name, long size, String crc) { }
    private final Transport transport;
    private final int maximumBytes;
    private long lastRequest;

    SrrdbClient(int maximumBytes) { this(maximumBytes, SrrdbClient::fetch); }
    SrrdbClient(int maximumBytes, Transport transport) {
        this.maximumBytes = maximumBytes;
        this.transport = transport;
    }

    List<RemoteFile> details(String release) throws IOException {
        byte[] bytes = request(URI.create("https://api.srrdb.com/v1/details/" + segment(release)), 4 * 1024 * 1024);
        try {
            JsonObject result = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!result.has("name") || !release.equalsIgnoreCase(result.get("name").getAsString())) {
                throw new IOException("No exact srrDB release match");
            }
            List<RemoteFile> files = new ArrayList<>();
            for (var value : result.getAsJsonArray("files")) {
                JsonObject file = value.getAsJsonObject();
                String name = file.get("name").getAsString();
                if (!metadataPath(name)) continue;
                long size = file.get("size").getAsLong();
                String crc = file.get("crc").getAsString();
                if (size > 0 && size <= maximumBytes && crc.matches("(?i)[0-9a-f]{8}")) {
                    files.add(new RemoteFile(name, size, crc));
                }
            }
            return files;
        } catch (RuntimeException e) {
            throw new IOException("Invalid srrDB details response", e);
        }
    }

    byte[] download(String release, RemoteFile file) throws IOException {
        if (!metadataPath(file.name()) || file.size() <= 0 || file.size() > maximumBytes) {
            throw new IOException("Invalid metadata file");
        }
        byte[] data = request(downloadUri(release, file.name()), maximumBytes);
        verify(file, data);
        return data;
    }

    static void verify(RemoteFile file, byte[] data) throws IOException {
        CRC32 crc = new CRC32();
        crc.update(data);
        if (data.length != file.size() || !String.format(Locale.ROOT, "%08X", crc.getValue()).equalsIgnoreCase(file.crc())) {
            throw new IOException("Downloaded size/CRC32 does not match srrDB metadata");
        }
        String text = new String(data, StandardCharsets.ISO_8859_1);
        String beginning = text.stripLeading().toLowerCase(Locale.ROOT);
        if (text.indexOf(0) >= 0 || beginning.startsWith("<!doctype html") || beginning.startsWith("<html")) {
            throw new IOException("Unexpected binary or HTML response");
        }
        if (file.name().toLowerCase(Locale.ROOT).endsWith(".sfv")) {
            boolean entry = false;
            for (String line : text.split("\\R")) {
                if (line.isBlank() || line.stripLeading().startsWith(";")) continue;
                var member = java.util.regex.Pattern.compile("^(.+?)[ \\t]+[0-9a-fA-F]{8}[ \\t]*$").matcher(line);
                if (!member.matches() || !relativePath(member.group(1))) throw new IOException("Invalid SFV member path or checksum");
                entry = true;
            }
            if (!entry) throw new IOException("SFV contains no checksum entries");
        }
    }

    static boolean metadataPath(String path) {
        if (!relativePath(path)) return false;
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".nfo") || lower.endsWith(".sfv");
    }

    private static boolean relativePath(String path) {
        if (path == null || path.length() > 1024 || path.startsWith("/") || path.indexOf(92) >= 0
                || path.contains(":") || path.contains("%") || path.chars().anyMatch(c -> c < 32 || c == 127)) return false;
        for (String part : path.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || !part.equals(part.strip())
                    || part.endsWith(".")) return false;
        }
        return true;
    }

    static URI downloadUri(String release, String name) {
        return URI.create("https://www.srrdb.com/download/file/" + segment(release) + "/" + segment(name));
    }
    static String segment(String text) { return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20"); }

    private byte[] request(URI uri, int limit) throws IOException {
        long delay = 1000 - (System.currentTimeMillis() - lastRequest);
        try {
            if (delay > 0) Thread.sleep(delay);
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("srrDB request cancelled", e);
        }
        lastRequest = System.currentTimeMillis();
        byte[] body = transport.get(uri, limit);
        if (body.length > limit) throw new IOException("srrDB response too large");
        return body;
    }

    private static byte[] fetch(URI uri, int maximumBytes) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("User-Agent", "DrFTPD-WebAdmin/4.0 (manual metadata recovery)");
        connection.setRequestProperty("Accept-Encoding", "identity");
        try {
            int status = connection.getResponseCode();
            if (status != 200) throw new IOException("srrDB HTTP " + status + " (file unavailable or access restricted)");
            if (connection.getContentLengthLong() > maximumBytes) throw new IOException("srrDB response too large");
            try (var input = connection.getInputStream()) {
                byte[] body = input.readNBytes(maximumBytes + 1);
                if (body.length > maximumBytes) throw new IOException("srrDB response too large");
                return body;
            }
        } finally { connection.disconnect(); }
    }
}
