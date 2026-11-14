package org.drftpd.webadmin.master;

import org.apache.commons.net.ProtocolCommandEvent;
import org.apache.commons.net.ProtocolCommandListener;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Deliberately not a raw FTP transcript: credentials and reply bodies stay private. */
final class SrrdbFtpTrace implements ProtocolCommandListener {
    private static final Set<String> SAFE_ARGUMENTS = Set.of(
            "AUTH", "PBSZ", "PROT", "TYPE", "CWD", "PRET", "PASV", "EPSV", "PORT", "EPRT", "STOR", "RETR", "SIZE", "XCRC", "QUIT");
    private final String id = UUID.randomUUID().toString();
    private final String password;
    private final Consumer<String> sink;
    private final long started = System.nanoTime();
    private volatile String lastCommand = "none";
    private volatile long commandStarted = started;
    private volatile int lastReply;

    SrrdbFtpTrace(String password, Consumer<String> sink) { this.password = password; this.sink = sink; }

    String clean(String value) {
        String text = value == null ? "unknown" : value;
        if (!password.isEmpty()) text = text.replace(password, "[REDACTED]");
        text = text.replaceAll("[\\p{Cntrl}]", " ");
        return text.length() <= 1024 ? text : text.substring(0, 1024) + " [truncated]";
    }

    void event(String event, String details) {
        sink.accept("srrDB FTPS trace: id=" + id + " elapsedMs="
                + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                + " event=" + event + " " + clean(details));
    }

    String state() { return "lastCommand=" + lastCommand + " lastReply=" + lastReply; }

    @Override public void protocolCommandSent(ProtocolCommandEvent event) {
        String command = event.getCommand().toUpperCase(Locale.ROOT);
        lastCommand = command.matches("[A-Z]{3,8}") ? command : "OTHER";
        commandStarted = System.nanoTime();
        String details = SAFE_ARGUMENTS.contains(command) ? event.getMessage() : command + " [arguments omitted]";
        event("command", details);
    }

    @Override public void protocolReplyReceived(ProtocolCommandEvent event) {
        lastReply = event.getReplyCode();
        event("reply", state() + " commandElapsedMs="
                + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - commandStarted));
        if (lastCommand.equals("PASV") && lastReply == 227) {
            // Includes DrFTPD's selected-slave comment and the advertised endpoint only.
            event("pasv-reply", event.getMessage());
        }
        if ((lastCommand.equals("STOR") || lastCommand.equals("RETR")) && (lastReply == 150 || lastReply == 125))
            event("transfer-preliminary", event.getMessage());
    }
}
