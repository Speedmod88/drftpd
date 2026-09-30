package org.drftpd.webadmin.master;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class WebAdminSessionTest {
    @Test void defaultsToFixedTwentyFourHourLifetime() {
        assertEquals(1440, new WebAdminSettings(new Properties()).sessionTimeoutMinutes);
        long now = 1000000;
        var token = new WebAdminServer.WebSessionToken("id", "csrf", "siteop", 1440, now);
        assertFalse(token.expired(now + TimeUnit.HOURS.toMillis(23)));
        assertFalse(token.expired(now + TimeUnit.HOURS.toMillis(24) - 1));
        assertTrue(token.expired(now + TimeUnit.HOURS.toMillis(24)));
        assertEquals(now + TimeUnit.HOURS.toMillis(24), token.expires);
    }
    @Test void privateHomeRedirectsAndRecoveryApiRequiresAuthentication() throws Exception {
        WebAdminServer panel = new WebAdminServer(new WebAdminSettings(new Properties()));
        var handle = WebAdminServer.class.getDeclaredMethod("handle", HttpExchange.class);
        handle.setAccessible(true);
        HttpServer listener = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        listener.createContext("/", exchange -> {
            try { handle.invoke(panel, exchange); }
            catch (Exception e) { throw new java.io.IOException(e); }
        });
        try {
            listener.start();
            for (String path : new String[]{"/home", "/api/srrdb", "/login", "/"}) {
                HttpURLConnection connection = (HttpURLConnection) URI.create("http://127.0.0.1:"
                        + listener.getAddress().getPort() + path).toURL().openConnection();
                connection.setInstanceFollowRedirects(false);
                try {
                    int code = connection.getResponseCode();
                    assertEquals(path.equals("/home") ? 303 : path.startsWith("/api/") ? 401 : 200, code);
                    if (code == 303) assertEquals("/login", connection.getHeaderField("Location"));
                    if (code == 200) {
                        String html = new String(connection.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        assertTrue(html.contains("id=\"appView\" class=\"app-shell\" hidden"));
                    }
                } finally { connection.disconnect(); }
            }
            var sessionsField = WebAdminServer.class.getDeclaredField("sessions");
            sessionsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            var sessions = (java.util.Map<String, WebAdminServer.WebSessionToken>) sessionsField.get(panel);
            sessions.put("expired", new WebAdminServer.WebSessionToken("expired", "csrf", "siteop", 1440, 0));
            HttpURLConnection expired = (HttpURLConnection) URI.create("http://127.0.0.1:"
                    + listener.getAddress().getPort() + "/api/srrdb").toURL().openConnection();
            expired.setRequestProperty("Cookie", "drftpd_webadmin=expired");
            expired.setRequestMethod("POST");
            try {
                assertEquals(401, expired.getResponseCode());
                assertFalse(sessions.containsKey("expired"));
                var recovery = WebAdminServer.class.getDeclaredField("recovery");
                recovery.setAccessible(true);
                assertNull(recovery.get(panel), "Unauthenticated calls must not start the recovery service");
            } finally { expired.disconnect(); }
        } finally { listener.stop(0); panel.stop(); }
    }
}
