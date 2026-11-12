package org.drftpd.webadmin.master;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.commons.net.ftp.FTPSClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import javax.net.ssl.SSLContext;
import java.io.*;
import java.net.*;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Missing metadata is uploaded by a normal, authenticated FTP session. */
final class SrrdbFtpUpload implements AutoCloseable {
    private static final Logger logger = LogManager.getLogger("org.drftpd.webadmin.srrdb");
    interface Hosts { InetAddress[] resolve(String host) throws UnknownHostException; }
    private final WebAdminSettings settings;
    private final Supplier<FTPSClient> clients;
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "srrDB-FTPS-deadline"); thread.setDaemon(true); return thread;
    });
    private volatile FTPSClient active;
    private volatile boolean closed;

    SrrdbFtpUpload(WebAdminSettings settings, Supplier<FTPSClient> clients) {
        this.settings = settings; this.clients = clients;
    }

    static final class Client extends FTPSClient {
        private volatile Socket data;
        private volatile boolean cancelled;
        Client(SSLContext context) { super(false, context); }
        @Override protected void _parsePassiveModeReply(String reply) throws org.apache.commons.net.MalformedServerReplyException {
            // Commons Net supplies the first line, but DrFTPD puts the endpoint on the final 227 line.
            for (String line : getReplyStrings()) {
                if (line.startsWith("227 ")) reply = line;
            }
            super._parsePassiveModeReply(reply);
            logger.info("srrDB FTPS passive data endpoint: host={} port={}", getPassiveHost(), getPassivePort());
        }
        @Override protected void _prepareDataSocket_(Socket socket) throws IOException {
            data = socket;
            if (cancelled) { socket.close(); throw new IOException("FTPS recovery cancelled"); }
            super._prepareDataSocket_(socket);
        }
        void cancelData() {
            cancelled = true;
            Socket socket = data;
            if (socket != null) try { socket.close(); } catch (IOException ignored) { }
        }
    }

    static InetSocketAddress endpoint(WebAdminSettings settings, Properties master) throws IOException {
        return endpoint(settings, master, InetAddress::getAllByName);
    }

    static InetSocketAddress endpoint(WebAdminSettings settings, Properties master, Hosts hosts) throws IOException {
        String host = settings.srrdbFtpHost;
        boolean explicitHost = !host.isEmpty();
        if (host.isEmpty()) host = master.getProperty("master.ip", "").trim();
        if (host.isEmpty() || host.equals("0.0.0.0") || host.equals("::")) host = "127.0.0.1";
        InetAddress chosen = null;
        for (InetAddress address : hosts.resolve(host)) {
            if (address instanceof Inet4Address && (explicitHost || address.isLoopbackAddress() || NetworkInterface.getByInetAddress(address) != null)) {
                chosen = address; break;
            }
        }
        if (chosen == null) throw new IOException(explicitHost ? "Configured recovery FTPS host has no IPv4 address"
                : "Automatic recovery FTPS host must resolve to a local IPv4 address of this master");
        int port = settings.srrdbFtpPort;
        if (port == 0) {
            try { port = Integer.parseInt(master.getProperty("master.port", "2121").trim()); }
            catch (NumberFormatException e) { throw new IOException("Invalid master FTP port", e); }
        }
        if (port < 1 || port > 65535) throw new IOException("Invalid master FTP port");
        return new InetSocketAddress(chosen, port);
    }

    String install(String username, String path, SrrdbClient.RemoteFile remote, byte[] bytes, Properties master) throws Exception {
        SrrdbClient.verify(remote, bytes);
        if (closed) throw new IOException("FTPS recovery is stopped");
        if (settings.srrdbFtpUser.isEmpty() || settings.srrdbFtpPassword.isEmpty())
            throw new IOException("Configure the dedicated srrdb.ftp.user and srrdb.ftp.pasword in webadmin.conf");
        InetSocketAddress endpoint = endpoint(settings, master);
        String parent = path.substring(0, path.lastIndexOf('/'));
        String name = path.substring(path.lastIndexOf('/') + 1);
        String stage = "initialize dedicated FTP client";
        FTPSClient ftp = clients.get();
        active = ftp;
        var deadline = deadlines.schedule(() -> disconnect(ftp), 120, TimeUnit.SECONDS);
        try {
            ftp.setConnectTimeout(10000);
            ftp.setDefaultTimeout(20000);
            ftp.setDataTimeout(Duration.ofSeconds(30));
            ftp.setControlEncoding("UTF-8");
            ftp.setUseEPSVwithIPv4(false);
            ftp.setIpAddressFromPasvResponse(true);
            ftp.setPassiveNatWorkaroundStrategy(null);
            // Distributed FTP data comes from the selected slave, not the master control peer.
            ftp.setRemoteVerificationEnabled(false);
            logger.info("srrDB FTPS import started: user={} master={} path={} size={} crc={}", username, endpoint, path, bytes.length, remote.crc());
            stage = "connect to master";
            ftp.connect(endpoint.getAddress(), endpoint.getPort());
            require(FTPReply.isPositiveCompletion(ftp.getReplyCode()), ftp, "Master FTP greeting");
            stage = "FTP login";
            require(ftp.login(settings.srrdbFtpUser, settings.srrdbFtpPassword), ftp, "FTP login (check IP permissions and login slots)");
            stage = "configure protected FTP transfer";
            ftp.execPBSZ(0);
            ftp.execPROT("P");
            require(ftp.setFileType(FTP.BINARY_FILE_TYPE), ftp, "TYPE I");
            ftp.enterLocalPassiveMode();
            stage = "FTP CWD";
            require(ftp.changeWorkingDirectory(parent), ftp, "CWD " + parent);
            stage = "FTP PRET STOR";
            require(FTPReply.isPositiveCompletion(ftp.sendCommand("PRET", "STOR " + name)), ftp, "PRET STOR " + name);
            stage = "FTP passive data connection / STOR";
            try (var input = new ByteArrayInputStream(bytes)) {
                require(ftp.storeFile(name, input), ftp, "STOR " + name);
            }
            stage = "verify uploaded metadata";
            String size = ftp.getSize(name);
            if (size == null || !size.trim().equals(Long.toString(bytes.length))) throw new IOException("FTP SIZE differs from approved metadata; uploaded file kept");
            if (name.chars().noneMatch(Character::isWhitespace)) {
                require(FTPReply.isPositiveCompletion(ftp.sendCommand("XCRC", name)), ftp, "XCRC " + name);
                var match = java.util.regex.Pattern.compile("(?is).*\\b([0-9a-f]{8})\\s*$").matcher(ftp.getReplyString().trim());
                if (!match.matches() || !match.group(1).equalsIgnoreCase(remote.crc()))
                    throw new IOException("FTP XCRC differs from approved metadata; uploaded file kept");
            } else {
                // The existing XCRC command tokenizes filenames on spaces; verify these by FTP RETR.
                ByteArrayOutputStream copy = new ByteArrayOutputStream() {
                    @Override public synchronized void write(int value) {
                        if (count == bytes.length) throw new IllegalStateException("FTP verification exceeded metadata size");
                        super.write(value);
                    }
                    @Override public synchronized void write(byte[] data, int offset, int length) {
                        if (count + length > bytes.length) throw new IllegalStateException("FTP verification exceeded metadata size");
                        super.write(data, offset, length);
                    }
                };
                require(FTPReply.isPositiveCompletion(ftp.sendCommand("PRET", "RETR " + name)), ftp, "PRET RETR");
                require(ftp.retrieveFile(name, copy), ftp, "RETR verification");
                SrrdbClient.verify(remote, copy.toByteArray());
            }
            logger.info("srrDB FTPS import verified: user={} master={} path={}", username, endpoint, path);
            return "master FTPS " + endpoint;
        } catch (Exception e) {
            String passive = ftp.getPassiveHost() == null ? "not advertised" : ftp.getPassiveHost() + ":" + ftp.getPassivePort();
            throw new IOException("srrDB download succeeded; " + stage + " failed (master=" + endpoint
                    + ", passive=" + passive + "): " + e.getMessage(), e);
        } finally {
            deadline.cancel(false);
            disconnect(ftp);
            active = null;
        }
    }

    private void require(boolean success, FTPSClient ftp, String command) throws IOException {
        if (!success) {
            String reply = String.valueOf(ftp.getReplyString()).replaceAll("[\\r\\n]+", " ");
            if (!settings.srrdbFtpPassword.isEmpty()) reply = reply.replace(settings.srrdbFtpPassword, "[REDACTED]");
            if (reply.length() > 400) reply = reply.substring(0, 400);
            throw new IOException(command + " refused: " + reply);
        }
    }
    private static void disconnect(FTPSClient ftp) {
        if (ftp instanceof Client client) client.cancelData();
        try { ftp.disconnect(); } catch (IOException ignored) { }
    }
    @Override public void close() { closed = true; FTPSClient ftp = active; if (ftp != null) disconnect(ftp); deadlines.shutdownNow(); }
}
