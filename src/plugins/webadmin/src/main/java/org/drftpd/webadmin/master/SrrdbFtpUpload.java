package org.drftpd.webadmin.master;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.commons.net.ftp.FTPSClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
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
        private volatile SrrdbFtpTrace trace;
        private boolean dataSocketReady;
        Client(SSLContext context) { super(false, context); }
        @Override public int port(InetAddress address, int port) throws IOException {
            if (trace != null) trace.event("active-advertised", "host=" + address.getHostAddress() + " port=" + port);
            return super.port(address, port);
        }
        @Override public int eprt(InetAddress address, int port) throws IOException {
            if (trace != null) trace.event("active-advertised", "host=" + address.getHostAddress() + " port=" + port);
            return super.eprt(address, port);
        }
        @Override protected void _connectAction_() throws IOException {
            var diagnostic = trace;
            if (diagnostic != null) diagnostic.event("control-tcp-connected", endpoints(_socket_));
            super._connectAction_();
            if (diagnostic != null) diagnostic.event("control-ready", endpoints(_socket_) + tls(_socket_));
        }
        @Override protected void _parsePassiveModeReply(String reply) throws org.apache.commons.net.MalformedServerReplyException {
            // Commons Net supplies the first line, but DrFTPD puts the endpoint on the final 227 line.
            for (String line : getReplyStrings()) {
                if (line.startsWith("227 ")) reply = line;
            }
            super._parsePassiveModeReply(reply);
            logger.info("srrDB FTPS passive data endpoint: host={} port={}", getPassiveHost(), getPassivePort());
            if (trace != null) trace.event("pasv-advertised", "host=" + getPassiveHost() + " port=" + getPassivePort()
                    + " addressSource=master-PASV-reply noAddressOverride=true");
        }
        @Override protected Socket _openDataConnection_(String command, String argument) throws IOException {
            var diagnostic = trace;
            dataSocketReady = false;
            if (diagnostic != null) diagnostic.event("data-open-start", "operation=" + command);
            try {
                Socket socket = super._openDataConnection_(command, argument);
                if (diagnostic != null) diagnostic.event(socket == null ? "data-open-refused" : "data-ready",
                        diagnostic.state() + " " + endpoints(socket) + tls(socket));
                return socket;
            } catch (IOException e) {
                if (diagnostic != null) diagnostic.event("data-open-failed", "mode=" + getDataConnectionMode() + " " + diagnostic.state()
                        + " dataSocketReady=" + dataSocketReady + " passive=" + getPassiveHost() + ":" + getPassivePort()
                        + " " + endpoints(dataSocketReady ? data : null) + " cause=" + e.getClass().getName() + ": " + e.getMessage());
                throw e;
            }
        }
        @Override protected void _prepareDataSocket_(Socket socket) throws IOException {
            data = socket;
            if (cancelled) { if (socket != null) socket.close(); throw new IOException("FTPS recovery cancelled"); }
            dataSocketReady = socket != null && socket.isConnected();
            // Commons Net invokes this after TCP connect and the preliminary STOR/RETR reply, before data TLS.
            if (trace != null && socket != null) trace.event("data-socket-prepared", endpoints(socket) + " dataTLSHandshakeComplete=false");
            super._prepareDataSocket_(socket);
        }
        private static String endpoints(Socket socket) {
            return socket == null ? "local=unknown remote=unknown" : "local=" + socket.getLocalSocketAddress()
                    + " remote=" + socket.getRemoteSocketAddress() + " closed=" + socket.isClosed();
        }
        private static String tls(Socket socket) {
            return socket instanceof SSLSocket ssl ? " tls=" + ssl.getSession().getProtocol()
                    + " cipher=" + ssl.getSession().getCipherSuite() : "";
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
        Inet4Address activeAddress = settings.srrdbFtpActive ? SrrdbActiveAddress.choose(settings, master) : null;
        String mode = settings.srrdbFtpActive ? "PORT" : "PASV";
        String parent = path.substring(0, path.lastIndexOf('/'));
        String name = path.substring(path.lastIndexOf('/') + 1);
        String stage = "initialize dedicated FTP client";
        FTPSClient ftp = clients.get();
        SrrdbFtpTrace trace = new SrrdbFtpTrace(settings.srrdbFtpPassword, logger::info);
        ftp.addProtocolCommandListener(trace);
        if (ftp instanceof Client client) client.trace = trace;
        active = ftp;
        var deadline = deadlines.schedule(() -> {
            trace.event("deadline", "limitMs=120000 " + trace.state());
            disconnect(ftp);
        }, 120, TimeUnit.SECONDS);
        try {
            ftp.setConnectTimeout(10000);
            ftp.setDefaultTimeout(20000);
            ftp.setDataTimeout(Duration.ofSeconds(30));
            ftp.setControlEncoding("UTF-8");
            ftp.setUseClientMode(true);
            ftp.setUseEPSVwithIPv4(false);
            ftp.setIpAddressFromPasvResponse(true);
            ftp.setPassiveNatWorkaroundStrategy(null);
            // Distributed FTP data comes from the selected slave, not the master control peer.
            ftp.setRemoteVerificationEnabled(false);
            logger.info("srrDB FTPS import started: user={} master={} path={} size={} crc={}", username, endpoint, path, bytes.length, remote.crc());
            trace.event("start", "master=" + endpoint + " endpointSource="
                    + (settings.srrdbFtpHost.isEmpty() ? "automatic-local-master" : "configured-host")
                    + " connectTimeoutMs=10000 controlTimeoutMs=20000 dataTimeoutMs=30000 mode=" + mode + " protection=PROT-P path=" + path);
            stage = "connect to master";
            if (activeAddress == null) ftp.connect(endpoint.getAddress(), endpoint.getPort());
            else {
                // Keep PORT's source address consistent with normal private-address/FXP checks.
                trace.event("active-control-bind", "local=" + activeAddress + " remote=" + endpoint);
                ftp.connect(endpoint.getAddress(), endpoint.getPort(), activeAddress, 0);
            }
            require(FTPReply.isPositiveCompletion(ftp.getReplyCode()), ftp, "Master FTP greeting");
            trace.event("control-connected", "local=" + ftp.getLocalAddress() + " remote=" + ftp.getRemoteAddress());
            stage = "FTP login";
            require(ftp.login(settings.srrdbFtpUser, settings.srrdbFtpPassword), ftp, "FTP login (check IP permissions and login slots)");
            trace.event("login-complete", "dedicatedCredentials=true");
            stage = "configure protected FTP transfer";
            ftp.execPBSZ(0);
            ftp.execPROT("P");
            require(ftp.setFileType(FTP.BINARY_FILE_TYPE), ftp, "TYPE I");
            if (activeAddress == null) ftp.enterLocalPassiveMode();
            else {
                stage = "configure active FTPS listener";
                ftp.setActiveExternalIPAddress(activeAddress.getHostAddress());
                Inet4Address advertised = SrrdbActiveAddress.advertised(settings.srrdbFtpActiveAdvertisedHost, activeAddress);
                ftp.setReportActiveExternalIPAddress(advertised.getHostAddress());
                if (settings.srrdbFtpActivePortMin > 0)
                    ftp.setActivePortRange(settings.srrdbFtpActivePortMin, settings.srrdbFtpActivePortMax);
                ftp.enterLocalActiveMode();
                trace.event("active-configured", "bind=" + activeAddress.getHostAddress() + " advertised="
                        + advertised.getHostAddress()
                        + " ports=" + settings.srrdbFtpActivePortMin + "-" + settings.srrdbFtpActivePortMax
                        + " dataTLSRole=client");
            }
            stage = "FTP CWD";
            require(ftp.changeWorkingDirectory(parent), ftp, "CWD " + parent);
            stage = "FTP PRET STOR";
            require(FTPReply.isPositiveCompletion(ftp.sendCommand("PRET", "STOR " + name)), ftp, "PRET STOR " + name);
            trace.event("pret-accepted", "next=" + (settings.srrdbFtpActive
                    ? "PORT-then-STOR-then-accept-slave" : "PASV-data-connect-then-STOR"));
            stage = "FTP " + (settings.srrdbFtpActive ? "active" : "passive") + " data connection / STOR";
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
            trace.event("verified", "size=" + bytes.length + " crc=" + remote.crc());
            return "master FTPS " + endpoint;
        } catch (Exception e) {
            String passive = ftp.getPassiveHost() == null ? "not advertised" : ftp.getPassiveHost() + ":" + ftp.getPassivePort();
            String dataEndpoint = activeAddress == null ? "passive=" + passive : "activeBind=" + activeAddress
                    + " advertised=" + (settings.srrdbFtpActiveAdvertisedHost.isEmpty() ? activeAddress : settings.srrdbFtpActiveAdvertisedHost);
            trace.event("failed", "stage=" + stage + " mode=" + mode + " " + trace.state() + " " + dataEndpoint
                    + " cause=" + e.getClass().getName() + ": " + e.getMessage());
            throw new IOException("srrDB download succeeded; " + stage + " failed (master=" + endpoint
                    + ", " + dataEndpoint + "): " + e.getMessage(), e);
        } finally {
            deadline.cancel(false);
            disconnect(ftp);
            ftp.removeProtocolCommandListener(trace);
            if (ftp instanceof Client client) client.trace = null;
            trace.event("closed", "stage=" + stage);
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
