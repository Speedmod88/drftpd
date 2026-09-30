/*
 * This file is part of DrFTPD, Distributed FTP Daemon.
 *
 * DrFTPD is free software; you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 2 of the License, or (at your option) any later
 * version.
 */
package org.drftpd.webadmin.master;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.drftpd.master.GlobalContext;
import org.drftpd.master.commands.CommandManagerInterface;
import org.drftpd.master.commands.CommandRequestInterface;
import org.drftpd.master.commands.CommandResponseInterface;
import org.drftpd.master.exceptions.SlaveUnavailableException;
import org.drftpd.master.network.FtpReply;
import org.drftpd.master.network.Session;
import org.drftpd.master.slavemanagement.RemoteSlave;
import org.drftpd.master.slavemanagement.SlaveStatus;
import org.drftpd.master.usermanager.User;
import org.drftpd.master.vfs.DirectoryHandle;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class WebAdminServer {
    private static final Logger logger = LogManager.getLogger(WebAdminServer.class);
    private static final String SESSION_COOKIE = "drftpd_webadmin";
    private static final int MAX_LOGIN_BODY = 16 * 1024;
    private static final int MAX_COMMAND_BODY = 128 * 1024;
    private static final int MAX_COMMAND_OUTPUT_LINES = 10_000;
    private static final int MAX_LOG_BYTES = 2 * 1024 * 1024;
    private static final DateTimeFormatter BACKUP_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);
    private static final Map<String, String> STATIC_TYPES = Map.of(
            "/index.html", "text/html; charset=utf-8",
            "/app.js", "application/javascript; charset=utf-8",
            "/styles.css", "text/css; charset=utf-8");

    private final WebAdminSettings settings;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private final SecureRandom random = new SecureRandom();
    private final Map<String, LoginAttempt> loginAttempts = new ConcurrentHashMap<>();
    private final Map<String, WebSessionToken> sessions = new ConcurrentHashMap<>();
    private final Map<String, CommandJob> jobs = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor requestExecutor;
    private final ThreadPoolExecutor commandExecutor;

    private HttpsServer server;
    private SrrdbRecovery recovery;
    private boolean stopping;

    WebAdminServer(WebAdminSettings settings) {
        this.settings = Objects.requireNonNull(settings);
        requestExecutor = executor(settings.requestThreads, settings.requestQueue, "WebAdmin-HTTP");
        commandExecutor = executor(settings.commandThreads, settings.commandQueue, "WebAdmin-Command");
    }

    void start() {
        SSLContext sslContext = GlobalContext.getGlobalContext().getSSLContext();
        if (sslContext == null) {
            throw new IllegalStateException("Master SSL is not configured; webadmin refuses to start without HTTPS");
        }
        try {
            Files.createDirectories(settings.configRoot);
            Files.createDirectories(settings.logsRoot);
            server = HttpsServer.create(new InetSocketAddress(settings.bindAddress, settings.port), 0);
            server.setHttpsConfigurator(new HttpsConfigurator(sslContext) {
                @Override
                public void configure(HttpsParameters parameters) {
                    SSLParameters sslParameters = getSSLContext().getDefaultSSLParameters();
                    String[] protocols = GlobalContext.getConfig().getSSLProtocols();
                    String[] ciphers = GlobalContext.getConfig().getCipherSuites();
                    if (protocols != null) {
                        sslParameters.setProtocols(protocols);
                    }
                    if (ciphers != null) {
                        sslParameters.setCipherSuites(ciphers);
                    }
                    parameters.setSSLParameters(sslParameters);
                }
            });
            server.createContext("/", this::handle);
            server.setExecutor(requestExecutor);
            server.start();
            logger.info("HTTPS web administration listening on https://{}:{}; login restricted to siteop",
                    settings.bindAddress.getHostAddress(), settings.port);
            if (settings.bindAddress.isLoopbackAddress()) {
                logger.warn("WebAdmin is bound to loopback only ({}). Direct LAN connections cannot reach it. "
                                + "Use an SSH tunnel, or configure bind in config/plugins/webadmin.conf "
                                + "to an address assigned to the master and restrict access with a firewall",
                        settings.bindAddress.getHostAddress());
            }
            logger.debug("WebAdmin paths: configRoot={} logsRoot={}", settings.configRoot, settings.logsRoot);
        } catch (IOException e) {
            stop();
            throw new IllegalStateException("Unable to bind HTTPS web administration server", e);
        }
    }

    void stop() {
        synchronized (this) {
            stopping = true;
            if (recovery != null) recovery.close();
        }
        HttpsServer current = server;
        server = null;
        if (current != null) {
            current.stop(1);
        }
        requestExecutor.shutdownNow();
        commandExecutor.shutdownNow();
        sessions.clear();
        logger.info("HTTPS web administration server stopped");
    }

    private void handle(HttpExchange exchange) {
        long startedAt = System.nanoTime();
        // The raw path excludes query values and keeps control characters escaped.
        String requestPath = exchange.getRequestURI().getRawPath();
        try {
            secureHeaders(exchange);
            String path = exchange.getRequestURI().getPath();
            if ("/api/login".equals(path)) {
                login(exchange);
                return;
            }
            if (!path.startsWith("/api/")) {
                staticResource(exchange, path);
                return;
            }

            WebSessionToken session = requireSession(exchange);
            if (isMutation(exchange)) {
                requireCsrf(exchange, session);
            }

            switch (path) {
                case "/api/session" -> session(exchange, session);
                case "/api/logout" -> logout(exchange, session);
                case "/api/overview" -> overview(exchange, session);
                case "/api/timers" -> timers(exchange);
                case "/api/config/files" -> configFiles(exchange);
                case "/api/config/file" -> configFile(exchange, session);
                case "/api/logs/files" -> logFiles(exchange);
                case "/api/logs/tail" -> logTail(exchange);
                case "/api/commands" -> commands(exchange, session);
                case "/api/restart" -> restart(exchange, session);
                case "/api/srrdb" -> srrdb(exchange, session);
                default -> {
                    if (path.startsWith("/api/jobs/")) {
                        commandJob(exchange, session, path.substring("/api/jobs/".length()));
                    } else {
                        throw new ApiException(404, "Not found");
                    }
                }
            }
        } catch (ApiException e) {
            sendError(exchange, e.status, e.getMessage());
        } catch (RejectedExecutionException e) {
            logger.warn("WebAdmin request queue full: method={} path={} remote={}",
                    exchange.getRequestMethod(), requestPath, exchange.getRemoteAddress());
            sendError(exchange, 503, "The web administration queue is full");
        } catch (Exception e) {
            logger.error("Web administration request failed: {} {}", exchange.getRequestMethod(),
                    requestPath, e);
            sendError(exchange, 500, "Request failed");
        } finally {
            logger.debug("WebAdmin request: method={} path={} remote={} status={} durationMs={}",
                    exchange.getRequestMethod(), requestPath, exchange.getRemoteAddress(),
                    exchange.getResponseCode(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
            exchange.close();
        }
    }

    private void login(HttpExchange exchange) throws IOException {
        requireMethod(exchange, "POST");
        String address = exchange.getRemoteAddress().getAddress().getHostAddress();
        LoginAttempt attempt = loginAttempts.computeIfAbsent(address, ignored -> new LoginAttempt());
        if (!attempt.allowed(settings.loginMaxAttempts, settings.loginWindowSeconds)) {
            throw new ApiException(429, "Too many login attempts. Try again later.");
        }

        LoginPayload payload = readJson(exchange, LoginPayload.class, MAX_LOGIN_BODY);
        if (payload == null || blank(payload.username) || payload.password == null) {
            attempt.failure();
            throw new ApiException(401, "Invalid username or password");
        }

        User user = authenticate(payload.username.trim(), payload.password);
        if (user == null) {
            attempt.failure();
            logger.warn("Rejected webadmin login for user [{}] from {}", payload.username, address);
            throw new ApiException(401, "Invalid username or password");
        }

        attempt.success();
        sessions.values().removeIf(WebSessionToken::expired);
        if (sessions.size() >= 1000) throw new ApiException(503, "Too many active sessions");
        String previousSession = cookie(exchange, SESSION_COOKIE);
        if (previousSession != null) sessions.remove(previousSession);
        WebSessionToken session = new WebSessionToken(randomToken(), randomToken(), user.getName(),
                settings.sessionTimeoutMinutes);
        sessions.put(session.id, session);
        exchange.getResponseHeaders().add("Set-Cookie", SESSION_COOKIE + "=" + session.id
                + "; Path=/; Max-Age=" + settings.sessionTimeoutMinutes * 60
                + "; Secure; HttpOnly; SameSite=Strict");
        logger.info("Webadmin login accepted for siteop [{}] from {}", user.getName(), address);
        sendJson(exchange, 200, sessionView(session));
    }

    private User authenticate(String username, String password) {
        try {
            User user = GlobalContext.getGlobalContext().getUserManager().getUserByNameIncludeDeleted(username);
            if (user.isDeleted() || !user.isMemberOf("siteop") || !GlobalContext.getConfig().isLoginAllowed(user)) {
                return null;
            }
            return user.checkPassword(password) ? user : null;
        } catch (Exception e) {
            return null;
        }
    }

    private WebSessionToken requireSession(HttpExchange exchange) {
        String id = cookie(exchange, SESSION_COOKIE);
        WebSessionToken session = id == null ? null : sessions.get(id);
        if (session == null || session.expired()) {
            if (id != null) {
                sessions.remove(id);
            }
            throw new ApiException(401, "Authentication required");
        }
        try {
            User user = GlobalContext.getGlobalContext().getUserManager()
                    .getUserByNameIncludeDeleted(session.username);
            if (user.isDeleted() || !user.isMemberOf("siteop") || !GlobalContext.getConfig().isLoginAllowed(user)) {
                sessions.remove(session.id);
                throw new ApiException(401, "Authentication required");
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            sessions.remove(session.id);
            throw new ApiException(401, "Authentication required");
        }
        return session;
    }

    private void requireCsrf(HttpExchange exchange, WebSessionToken session) {
        String supplied = exchange.getRequestHeaders().getFirst("X-CSRF-Token");
        if (supplied == null || !MessageDigest.isEqual(
                supplied.getBytes(StandardCharsets.UTF_8), session.csrf.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(403, "Invalid CSRF token");
        }
    }

    private void session(HttpExchange exchange, WebSessionToken session) throws IOException {
        requireMethod(exchange, "GET");
        sendJson(exchange, 200, sessionView(session));
    }

    private Map<String, Object> sessionView(WebSessionToken session) {
        return Map.of("username", session.username, "csrf", session.csrf,
                "expiresAt", session.expires, "serverTime", System.currentTimeMillis());
    }

    private synchronized SrrdbRecovery recovery() throws IOException {
        if (stopping) throw new ApiException(503, "WebAdmin is stopping");
        if (recovery == null) {
            recovery = new SrrdbRecovery(settings.srrdbState, settings.srrdbScanLimit,
                    new SrrdbClient(settings.srrdbMaximumBytes), new SrrdbLibrary());
        }
        return recovery;
    }

    private void srrdb(HttpExchange exchange, WebSessionToken session) throws IOException {
        if (exchange.getRequestMethod().equals("GET")) {
            sendJson(exchange, 200, recovery().view());
            return;
        }
        requireMethod(exchange, "POST");
        RecoveryPayload payload = readJson(exchange, RecoveryPayload.class, MAX_LOGIN_BODY);
        if (payload == null || payload.action == null) throw new ApiException(400, "An action is required");
        try {
            switch (payload.action) {
                case "scan" -> recovery().scan(session.username, payload.path, payload.recursive);
                case "cancel" -> recovery().cancel();
                case "clear" -> recovery().clearFinished();
                case "accept", "reject" -> recovery().decide(payload.id, payload.action, session.username);
                default -> throw new IllegalArgumentException("Unknown recovery action");
            }
        } catch (IllegalArgumentException e) { throw new ApiException(400, e.getMessage()); }
        sendJson(exchange, 202, recovery().view());
    }

    private void logout(HttpExchange exchange, WebSessionToken session) throws IOException {
        requireMethod(exchange, "POST");
        sessions.remove(session.id);
        exchange.getResponseHeaders().add("Set-Cookie",
                SESSION_COOKIE + "=; Path=/; Max-Age=0; Secure; HttpOnly; SameSite=Strict");
        sendJson(exchange, 200, Map.of("ok", true));
    }

    private void overview(HttpExchange exchange, WebSessionToken session) throws IOException {
        requireMethod(exchange, "GET");
        List<Map<String, Object>> slaves = new ArrayList<>();
        int online = 0;
        int remerging = 0;
        long free = 0;
        long capacity = 0;
        for (RemoteSlave slave : GlobalContext.getGlobalContext().getSlaveManager().getSlaves()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", slave.getName());
            item.put("online", slave.isOnline());
            item.put("available", slave.isAvailable());
            item.put("remerging", slave.isRemerging());
            item.put("renameQueue", slave.getRenameQueue().size());
            item.put("remergeQueue", slave.getRemergeQueue().size());
            item.put("crcQueue", slave.getCRCQueue().size());
            if (slave.isOnline()) {
                online++;
                if (slave.isRemerging()) {
                    remerging++;
                }
                try {
                    SlaveStatus status = slave.getSlaveStatus();
                    item.put("freeBytes", status.getDiskSpaceAvailable());
                    item.put("capacityBytes", status.getDiskSpaceCapacity());
                    item.put("uploadTransfers", status.getTransfersReceiving());
                    item.put("downloadTransfers", status.getTransfersSending());
                    item.put("uploadBytesPerSecond", status.getThroughputReceiving());
                    item.put("downloadBytesPerSecond", status.getThroughputSending());
                    free += status.getDiskSpaceAvailable();
                    capacity += status.getDiskSpaceCapacity();
                } catch (SlaveUnavailableException ignored) {
                    item.put("online", false);
                }
            }
            slaves.add(item);
        }
        slaves.sort(Comparator.comparing(value -> value.get("name").toString().toLowerCase(Locale.ROOT)));

        Runtime runtime = Runtime.getRuntime();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("username", session.username);
        response.put("uptimeMillis", ManagementFactory.getRuntimeMXBean().getUptime());
        response.put("heapUsedBytes", runtime.totalMemory() - runtime.freeMemory());
        response.put("heapMaximumBytes", runtime.maxMemory());
        response.put("processors", runtime.availableProcessors());
        response.put("onlineSlaves", online);
        response.put("totalSlaves", slaves.size());
        response.put("remergingSlaves", remerging);
        response.put("freeBytes", free);
        response.put("capacityBytes", capacity);
        response.put("slaves", slaves);
        response.put("allowedCommands", settings.allowedCommands);
        sendJson(exchange, 200, response);
    }

    private void timers(HttpExchange exchange) throws IOException {
        requireMethod(exchange, "GET");
        List<Map<String, Object>> timers = new ArrayList<>();
        for (GlobalContext.TimerStatus status : GlobalContext.getGlobalContext().getTimerStatuses()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", status.getName());
            item.put("owner", status.getOwner());
            item.put("delayMillis", status.getDelay());
            item.put("periodMillis", status.getPeriod());
            item.put("fixedRate", status.isFixedRate());
            item.put("lastRun", status.getLastRun());
            item.put("lastError", status.getLastError());
            item.put("enabled", status.isEnabled());
            item.put("running", status.isRunning());
            timers.add(item);
        }
        sendJson(exchange, 200, Map.of(
                "timers", timers,
                "timeEvents", GlobalContext.getGlobalContext().getTimeEventNames()));
    }

    private void configFiles(HttpExchange exchange) throws IOException {
        requireMethod(exchange, "GET");
        List<Map<String, Object>> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(settings.configRoot)) {
            stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.endsWith(".conf") || name.endsWith(".conf.dist");
                    })
                    .filter(path -> !settings.configRoot.relativize(path).startsWith(".webadmin-backups"))
                    .sorted()
                    .forEach(path -> {
                        try {
                            Map<String, Object> item = new LinkedHashMap<>();
                            item.put("path", portable(settings.configRoot.relativize(path)));
                            item.put("size", Files.size(path));
                            item.put("modified", Files.getLastModifiedTime(path).toMillis());
                            files.add(item);
                        } catch (IOException e) {
                            logger.debug("Skipping unreadable config file {}", path, e);
                        }
                    });
        }
        sendJson(exchange, 200, Map.of("files", files));
    }

    private void configFile(HttpExchange exchange, WebSessionToken session) throws IOException {
        if ("GET".equals(exchange.getRequestMethod())) {
            String relative = requiredQuery(exchange.getRequestURI(), "path");
            Path target = safeExistingFile(settings.configRoot, relative);
            byte[] bytes = Files.readAllBytes(target);
            if (bytes.length > settings.maximumConfigBytes) {
                throw new ApiException(413, "Configuration file is too large");
            }
            sendJson(exchange, 200, Map.of(
                    "path", portable(settings.configRoot.relativize(target)),
                    "content", new String(bytes, StandardCharsets.UTF_8),
                    "version", digest(bytes),
                    "modified", Files.getLastModifiedTime(target).toMillis()));
            return;
        }

        requireMethod(exchange, "PUT");
        ConfigPayload payload = readJson(exchange, ConfigPayload.class, settings.maximumConfigBytes + 64 * 1024);
        if (payload == null || blank(payload.path) || payload.content == null || blank(payload.version)) {
            throw new ApiException(400, "path, content and version are required");
        }
        Path target = safeExistingFile(settings.configRoot, payload.path);
        byte[] existing = Files.readAllBytes(target);
        if (!MessageDigest.isEqual(digest(existing).getBytes(StandardCharsets.US_ASCII),
                payload.version.getBytes(StandardCharsets.US_ASCII))) {
            throw new ApiException(409, "The file changed since it was opened. Reload it before saving.");
        }
        byte[] updated = payload.content.getBytes(StandardCharsets.UTF_8);
        if (updated.length > settings.maximumConfigBytes) {
            throw new ApiException(413, "Configuration file is too large");
        }

        Path relative = settings.configRoot.relativize(target);
        Path backup = settings.configRoot.resolve(".webadmin-backups")
                .resolve(BACKUP_FORMAT.format(Instant.now())).resolve(relative).normalize();
        if (!backup.startsWith(settings.configRoot.resolve(".webadmin-backups"))) {
            throw new ApiException(400, "Invalid configuration path");
        }
        Files.createDirectories(backup.getParent());
        Files.copy(target, backup, StandardCopyOption.COPY_ATTRIBUTES);

        Path temporary = Files.createTempFile(target.getParent(), ".webadmin-", ".tmp");
        try {
            Files.write(temporary, updated, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        logger.info("Webadmin user [{}] updated config [{}]; backup [{}]",
                session.username, portable(relative), portable(settings.configRoot.relativize(backup)));
        sendJson(exchange, 200, Map.of("path", portable(relative), "version", digest(updated),
                "modified", Files.getLastModifiedTime(target).toMillis()));
    }

    private void logFiles(HttpExchange exchange) throws IOException {
        requireMethod(exchange, "GET");
        List<Map<String, Object>> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(settings.logsRoot, 2)) {
            stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> path.getFileName().toString().endsWith(".log"))
                    .sorted()
                    .forEach(path -> {
                        try {
                            files.add(Map.of(
                                    "path", portable(settings.logsRoot.relativize(path)),
                                    "size", Files.size(path),
                                    "modified", Files.getLastModifiedTime(path).toMillis()));
                        } catch (IOException e) {
                            logger.debug("Skipping unreadable log file {}", path, e);
                        }
                    });
        }
        sendJson(exchange, 200, Map.of("files", files, "defaultLines", settings.defaultLogLines,
                "maximumLines", settings.maximumLogLines));
    }

    private void logTail(HttpExchange exchange) throws IOException {
        requireMethod(exchange, "GET");
        Map<String, String> query = query(exchange.getRequestURI());
        String relative = query.get("file");
        if (blank(relative)) {
            throw new ApiException(400, "file is required");
        }
        int lines = settings.defaultLogLines;
        if (query.containsKey("lines")) {
            try {
                lines = Integer.parseInt(query.get("lines"));
            } catch (NumberFormatException e) {
                throw new ApiException(400, "lines must be an integer");
            }
        }
        lines = Math.max(1, Math.min(settings.maximumLogLines, lines));
        Path target = safeExistingFile(settings.logsRoot, relative);
        sendJson(exchange, 200, Map.of(
                "file", portable(settings.logsRoot.relativize(target)),
                "content", tail(target, lines),
                "modified", Files.getLastModifiedTime(target).toMillis()));
    }

    private void commands(HttpExchange exchange, WebSessionToken session) throws IOException {
        requireMethod(exchange, "POST");
        CommandPayload payload = readJson(exchange, CommandPayload.class, MAX_COMMAND_BODY);
        if (payload == null || blank(payload.command)) {
            throw new ApiException(400, "command is required");
        }
        ResolvedCommand resolved = resolveCommand(payload.command);
        if (resolved == null || !settings.allowedCommands.contains(resolved.name)) {
            throw new ApiException(403, "Command is not enabled for web administration");
        }

        pruneJobs();
        CommandJob job = new CommandJob(UUID.randomUUID().toString(), session.username,
                resolved.name + (resolved.argument.isEmpty() ? "" : " " + resolved.argument));
        jobs.put(job.id, job);
        try {
            commandExecutor.execute(() -> runCommand(job, resolved));
        } catch (RejectedExecutionException e) {
            jobs.remove(job.id);
            throw e;
        }
        sendJson(exchange, 202, jobView(job));
    }

    private void runCommand(CommandJob job, ResolvedCommand resolved) {
        job.state = "running";
        job.started = System.currentTimeMillis();
        try {
            HashMap<String, Properties> commands = GlobalContext.getConnectionManager().getCommands();
            Properties properties = commands.get(resolved.name);
            if (properties == null) {
                throw new IllegalStateException("Command disappeared after reload: " + resolved.name);
            }
            WebCommandSession session = new WebCommandSession(job);
            session.setCommands(commands);
            CommandManagerInterface manager = GlobalContext.getConnectionManager().getCommandManager();
            CommandRequestInterface request = manager.newRequest(resolved.name, resolved.argument,
                    new DirectoryHandle("/"), job.username, session, properties);
            CommandResponseInterface response = manager.execute(request);
            if (response != null) {
                session.printOutput(new FtpReply(response));
            }
            job.state = "completed";
            logger.info("Webadmin user [{}] ran command [{}]", job.username, job.command);
        } catch (Throwable e) {
            job.state = "failed";
            job.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            logger.error("Webadmin command failed for user [{}]: {}", job.username, job.command, e);
        } finally {
            job.finished = System.currentTimeMillis();
        }
    }

    private void commandJob(HttpExchange exchange, WebSessionToken session, String id) throws IOException {
        requireMethod(exchange, "GET");
        CommandJob job = jobs.get(id);
        if (job == null || !job.username.equals(session.username)) {
            throw new ApiException(404, "Command job not found");
        }
        sendJson(exchange, 200, jobView(job));
    }

    private Map<String, Object> jobView(CommandJob job) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", job.id);
        view.put("command", job.command);
        view.put("created", job.created);
        view.put("state", job.state);
        view.put("started", job.started);
        view.put("finished", job.finished);
        view.put("error", job.error);
        view.put("output", job.snapshotOutput());
        view.put("truncated", job.truncated);
        return view;
    }

    private ResolvedCommand resolveCommand(String raw) {
        String chosen = null;
        int end = -1;
        for (String command : GlobalContext.getConnectionManager().getCommands().keySet()) {
            StringBuilder expression = new StringBuilder("(?i)^\\s*");
            String[] words = command.split(" ");
            for (int index = 0; index < words.length; index++) {
                if (index > 0) {
                    expression.append("\\s+");
                }
                expression.append(Pattern.quote(words[index]));
            }
            expression.append("(?=\\s|$)");
            Matcher matcher = Pattern.compile(expression.toString()).matcher(raw);
            if (matcher.find() && (chosen == null || command.length() > chosen.length())) {
                chosen = command;
                end = matcher.end();
            }
        }
        if (chosen == null) {
            return null;
        }
        return new ResolvedCommand(chosen.toLowerCase(Locale.ROOT), raw.substring(end).trim());
    }

    private void restart(HttpExchange exchange, WebSessionToken session) throws IOException {
        requireMethod(exchange, "POST");
        RestartPayload payload = readJson(exchange, RestartPayload.class, 4096);
        if (payload == null || !"restart".equals(payload.confirm)) {
            throw new ApiException(400, "Restart confirmation is required");
        }
        logger.warn("Webadmin user [{}] requested a graceful master restart", session.username);
        sendJson(exchange, 202, Map.of("message",
                "Master shutdown accepted. The service supervisor must be configured to restart it."));
        Thread restart = new Thread(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            GlobalContext.getGlobalContext().shutdown("Restart requested by webadmin user " + session.username);
        }, "WebAdmin-Restart");
        restart.setDaemon(false);
        restart.start();
    }

    private void staticResource(HttpExchange exchange, String requestPath) throws IOException {
        requireMethod(exchange, "GET");
        if ("/home".equals(requestPath)) {
            try { requireSession(exchange); }
            catch (ApiException e) {
                if (e.status != 401) throw e;
                exchange.getResponseHeaders().set("Location", "/login");
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                exchange.sendResponseHeaders(303, -1);
                return;
            }
        }
        String path = Set.of("/", "/login", "/home").contains(requestPath) ? "/index.html" : requestPath;
        String contentType = STATIC_TYPES.get(path);
        if (contentType == null) {
            throw new ApiException(404, "Not found");
        }
        try (InputStream input = WebAdminServer.class.getResourceAsStream("/webadmin" + path)) {
            if (input == null) {
                throw new ApiException(404, "Not found");
            }
            byte[] content = input.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, content.length);
            exchange.getResponseBody().write(content);
        }
    }

    private Path safeExistingFile(Path root, String relative) {
        if (blank(relative) || relative.indexOf('\0') >= 0) {
            throw new ApiException(400, "Invalid path");
        }
        Path requested = Path.of(relative.replace('/', java.io.File.separatorChar));
        if (requested.isAbsolute()) {
            throw new ApiException(400, "Absolute paths are not allowed");
        }
        Path target = root.resolve(requested).normalize();
        if (!target.startsWith(root) || target.startsWith(root.resolve(".webadmin-backups"))) {
            throw new ApiException(400, "Invalid path");
        }
        Path current = root;
        for (Path part : root.relativize(target)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                throw new ApiException(400, "Symbolic links are not allowed");
            }
        }
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new ApiException(404, "File not found");
        }
        return target;
    }

    private String tail(Path path, int requestedLines) throws IOException {
        long size = Files.size(path);
        int bytesToRead = (int) Math.min(size, MAX_LOG_BYTES);
        if (bytesToRead == 0) {
            return "";
        }
        ByteBuffer buffer = ByteBuffer.allocate(bytesToRead);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            channel.position(size - bytesToRead);
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                // Keep reading until the selected tail range is complete.
            }
        }
        String content = new String(buffer.array(), StandardCharsets.UTF_8);
        String[] lines = content.split("\\R", -1);
        int start = Math.max(0, lines.length - requestedLines - (content.endsWith("\n") ? 1 : 0));
        StringBuilder result = new StringBuilder();
        for (int index = start; index < lines.length; index++) {
            if (index == lines.length - 1 && lines[index].isEmpty()) {
                continue;
            }
            if (result.length() > 0) {
                result.append('\n');
            }
            result.append(lines[index]);
        }
        return result.toString();
    }

    private <T> T readJson(HttpExchange exchange, Class<T> type, int maximumBytes) throws IOException {
        byte[] body = exchange.getRequestBody().readNBytes(maximumBytes + 1);
        if (body.length > maximumBytes) {
            throw new ApiException(413, "Request body is too large");
        }
        try {
            return gson.fromJson(new String(body, StandardCharsets.UTF_8), type);
        } catch (JsonSyntaxException e) {
            throw new ApiException(400, "Invalid JSON");
        }
    }

    private void sendJson(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] body = gson.toJson(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    private void sendError(HttpExchange exchange, int status, String message) {
        try {
            sendJson(exchange, status, Map.of("error", message == null ? "Request failed" : message));
        } catch (IOException ignored) {
            // The client has already disconnected.
        }
    }

    private void secureHeaders(HttpExchange exchange) {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Security-Policy",
                "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
                        + "connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
        headers.set("Strict-Transport-Security", "max-age=31536000");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
    }

    private static void requireMethod(HttpExchange exchange, String method) {
        if (!method.equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", method);
            throw new ApiException(405, "Method not allowed");
        }
    }

    private static boolean isMutation(HttpExchange exchange) {
        return Set.of("POST", "PUT", "PATCH", "DELETE").contains(exchange.getRequestMethod());
    }

    private static String cookie(HttpExchange exchange, String name) {
        List<String> headers = exchange.getRequestHeaders().get("Cookie");
        if (headers == null) {
            return null;
        }
        for (String header : headers) {
            for (String item : header.split(";")) {
                String[] pair = item.trim().split("=", 2);
                if (pair.length == 2 && name.equals(pair[0])) {
                    return pair[1];
                }
            }
        }
        return null;
    }

    private static String requiredQuery(URI uri, String name) {
        String value = query(uri).get(name);
        if (blank(value)) {
            throw new ApiException(400, name + " is required");
        }
        return value;
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> values = new HashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null || raw.isEmpty()) {
            return values;
        }
        for (String item : raw.split("&")) {
            String[] pair = item.split("=", 2);
            String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
            String value = pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
            values.put(key, value);
        }
        return values;
    }

    private static String portable(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static String digest(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private String randomToken() {
        byte[] token = new byte[32];
        random.nextBytes(token);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }

    private void pruneJobs() {
        if (jobs.size() < 200) {
            return;
        }
        long cutoff = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1);
        jobs.values().removeIf(job -> job.finished > 0 && job.finished < cutoff);
        int excess = jobs.size() - 199;
        if (excess > 0) {
            jobs.values().stream()
                    .filter(job -> job.finished > 0)
                    .sorted(Comparator.comparingLong(job -> job.created))
                    .limit(excess)
                    .map(job -> job.id)
                    .toList()
                    .forEach(jobs::remove);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static ThreadPoolExecutor executor(int threads, int queue, String name) {
        return new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queue), new NamedThreadFactory(name),
                new ThreadPoolExecutor.AbortPolicy());
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private final String name;
        private int sequence;

        private NamedThreadFactory(String name) {
            this.name = name;
        }

        @Override
        public synchronized Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, name + "-" + ++sequence);
            thread.setDaemon(true);
            return thread;
        }
    }

    private static final class LoginAttempt {
        private long windowStart = System.currentTimeMillis();
        private int failures;

        synchronized boolean allowed(int maximum, int windowSeconds) {
            resetIfExpired(windowSeconds);
            return failures < maximum;
        }

        synchronized void failure() {
            failures++;
        }

        synchronized void success() {
            failures = 0;
            windowStart = System.currentTimeMillis();
        }

        private void resetIfExpired(int windowSeconds) {
            if (System.currentTimeMillis() - windowStart > TimeUnit.SECONDS.toMillis(windowSeconds)) {
                failures = 0;
                windowStart = System.currentTimeMillis();
            }
        }
    }

    static final class WebSessionToken {
        private final String id;
        private final String csrf;
        private final String username;
        final long expires;

        WebSessionToken(String id, String csrf, String username, int timeoutMinutes) {
            this(id, csrf, username, timeoutMinutes, System.currentTimeMillis());
        }

        WebSessionToken(String id, String csrf, String username, int timeoutMinutes, long now) {
            this.id = id;
            this.csrf = csrf;
            this.username = username;
            expires = now + TimeUnit.MINUTES.toMillis(timeoutMinutes);
        }

        private boolean expired() {
            return expired(System.currentTimeMillis());
        }

        boolean expired(long now) {
            return now >= expires;
        }
    }

    private static final class WebCommandSession extends Session {
        private final CommandJob job;

        private WebCommandSession(CommandJob job) {
            this.job = job;
        }

        @Override
        public boolean isSecure() {
            return true;
        }

        @Override
        public void printOutput(Object value) {
            addOutput(value);
        }

        @Override
        public void printOutput(int code, Object value) {
            addOutput(code + "- " + value);
        }

        private void addOutput(Object value) {
            String text = String.valueOf(value).replace("\r", "");
            for (String line : text.split("\n")) {
                if (!line.isEmpty()) {
                    job.addOutput(line);
                }
            }
        }
    }

    private static final class CommandJob {
        private final String id;
        private final String username;
        private final String command;
        private final long created = System.currentTimeMillis();
        private final List<String> output = new ArrayList<>();
        private volatile String state = "queued";
        private volatile long started;
        private volatile long finished;
        private volatile String error;
        private volatile boolean truncated;

        private CommandJob(String id, String username, String command) {
            this.id = id;
            this.username = username;
            this.command = command;
        }

        private synchronized void addOutput(String line) {
            if (output.size() < MAX_COMMAND_OUTPUT_LINES) {
                output.add(line);
            } else {
                truncated = true;
            }
        }

        private synchronized List<String> snapshotOutput() {
            return new ArrayList<>(output);
        }
    }

    private record ResolvedCommand(String name, String argument) { }

    private static final class LoginPayload {
        private String username;
        private String password;
    }

    private static final class ConfigPayload {
        private String path;
        private String content;
        private String version;
    }

    private static final class CommandPayload {
        private String command;
    }

    private static final class RestartPayload {
        private String confirm;
    }

    private static final class RecoveryPayload {
        private String action;
        private String id;
        private String path;
        private boolean recursive;
    }

    private static final class ApiException extends RuntimeException {
        private final int status;

        private ApiException(int status, String message) {
            super(message);
            this.status = status;
        }
    }
}
