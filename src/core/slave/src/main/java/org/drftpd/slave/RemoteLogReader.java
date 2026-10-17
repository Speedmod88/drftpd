package org.drftpd.slave;

import org.drftpd.slave.network.AsyncResponseLog;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.Properties;

/** Read-only, bounded access to a slave-local log directory, never the VFS. */
public final class RemoteLogReader {
    public static final String PROBE = "remote-log-tail-v1";
    public static final int SUPPORTED = -24001;
    public static final int DISABLED = -24002;
    public static final int MAX_BYTES = 262144;
    public static final int MAX_LINES = 2000;

    private RemoteLogReader() { }
    public static boolean enabled(Properties config) {
        return Boolean.parseBoolean(config.getProperty("remote.logs.enabled", "false"));
    }
    public static AsyncResponseLog read(String index, String[] args, Properties config) {
        try {
            if (!enabled(config)) throw new IOException("Remote logs are disabled in slave.conf");
            Path root = Path.of(config.getProperty("remote.logs.root", "logs")).toAbsolutePath().normalize();
            if (!root.equals(root.toRealPath()) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Log root must be a real directory, not a symbolic link");
            }
            if (args.length == 1 && args[0].equals("list")) {
                try (var stream = Files.list(root)) {
                    List<String> files = stream.filter(p -> validName(p.getFileName().toString()))
                            .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                            .limit(100).map(p -> p.getFileName().toString()).sorted().toList();
                    return new AsyncResponseLog(index, files, "", null);
                }
            }
            if (args.length != 3 || !args[0].equals("tail") || !validName(args[1])) {
                throw new IOException("Expected list or tail of a simple .log filename");
            }
            int lines = Integer.parseInt(args[2]);
            if (lines < 1 || lines > MAX_LINES) throw new IOException("Lines must be between 1 and 2000");
            Path file = root.resolve(args[1]);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Log file unavailable");
            byte[] bytes;
            boolean truncated;
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                long size = channel.size();
                int count = (int) Math.min(MAX_BYTES, size);
                truncated = size > count;
                channel.position(size - count);
                ByteBuffer buffer = ByteBuffer.allocate(count);
                while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Log read interrupted");
                }
                bytes = java.util.Arrays.copyOf(buffer.array(), buffer.position());
            }
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (truncated) {
                int newline = text.indexOf('\n');
                if (newline >= 0) text = text.substring(newline + 1);
            }
            String[] all = text.split("\\R", -1);
            int end = all.length;
            if (end > 0 && all[end - 1].isEmpty()) end--;
            String content = String.join("\n", java.util.Arrays.copyOfRange(all, Math.max(0, end - lines), end));
            return new AsyncResponseLog(index, List.of(), content, null);
        } catch (IOException | RuntimeException e) {
            // Ordinary access failures are data, not exceptions that could disconnect the slave.
            return new AsyncResponseLog(index, List.of(), "", e.getMessage() == null ? "Log read failed" : e.getMessage());
        }
    }
    static boolean validName(String name) {
        return name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,159}\\.log");
    }
}
