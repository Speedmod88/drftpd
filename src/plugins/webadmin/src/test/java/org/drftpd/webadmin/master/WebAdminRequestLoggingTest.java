package org.drftpd.webadmin.master;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class WebAdminRequestLoggingTest {
    @Test
    void failedLoginLogsStatusWithoutQueryCookieOrBodySecrets() throws Exception {
        var constructor = WebAdminSettings.class.getDeclaredConstructor(Properties.class);
        constructor.setAccessible(true);
        WebAdminServer webadmin = new WebAdminServer(constructor.newInstance(new Properties()));
        var handler = WebAdminServer.class.getDeclaredMethod("handle", HttpExchange.class);
        handler.setAccessible(true);
        HttpServer listener = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        listener.createContext("/", exchange -> {
            try {
                handler.invoke(webadmin, exchange);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new IOException(e);
            }
        });

        String packageName = "org.drftpd.webadmin";
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        var configuration = context.getConfiguration();
        LoggerConfig previous = configuration.getLoggers().get(packageName);
        RecordingAppender appender = new RecordingAppender();
        appender.start();
        LoggerConfig logger = new LoggerConfig(packageName, Level.DEBUG, false);
        logger.addAppender(appender, Level.DEBUG, null);
        configuration.addLogger(packageName, logger);
        context.updateLoggers();
        HttpURLConnection connection = null;
        try {
            listener.start();
            connection = (HttpURLConnection) URI.create("http://127.0.0.1:"
                    + listener.getAddress().getPort() + "/api/login?password=QUERY_SECRET").toURL()
                    .openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Cookie", "drftpd_webadmin=COOKIE_SECRET");
            connection.setDoOutput(true);
            // Missing username fails validation before accessing the live user database.
            try (var output = connection.getOutputStream()) {
                output.write(new Gson().toJson(Map.of("password", "BODY_SECRET"))
                        .getBytes(StandardCharsets.UTF_8));
            }
            assertEquals(401, connection.getResponseCode());
            try (var input = connection.getErrorStream()) {
                assertNotNull(input);
                input.readAllBytes();
            }
            assertTrue(appender.completed.await(5, TimeUnit.SECONDS));
            String messages = String.join("\n", appender.messages);
            assertTrue(messages.contains("method=POST path=/api/login "));
            assertTrue(messages.contains("status=401"));
            assertTrue(messages.contains("durationMs="));
            assertFalse(messages.contains("QUERY_SECRET"));
            assertFalse(messages.contains("COOKIE_SECRET"));
            assertFalse(messages.contains("BODY_SECRET"));
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
            listener.stop(0);
            webadmin.stop();
            configuration.removeLogger(packageName);
            if (previous != null) {
                configuration.addLogger(packageName, previous);
            }
            context.updateLoggers();
            appender.stop();
        }
    }

    private static final class RecordingAppender extends AbstractAppender {
        final List<String> messages = new CopyOnWriteArrayList<>();
        final CountDownLatch completed = new CountDownLatch(1);

        RecordingAppender() {
            super("webadmin-request-test", null, PatternLayout.createDefaultLayout(),
                    false, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            String message = event.getMessage().getFormattedMessage();
            messages.add(message);
            if (message.startsWith("WebAdmin request:")) {
                completed.countDown();
            }
        }
    }
}
