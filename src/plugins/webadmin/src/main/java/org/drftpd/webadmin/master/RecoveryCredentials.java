package org.drftpd.webadmin.master;

import java.io.IOException;
import java.util.*;
import java.util.function.LongSupplier;

/** Session-scoped secrets. Never serialized into recovery state, jobs or logs. */
final class RecoveryCredentials implements AutoCloseable {
    private final Map<String, Secret> secrets = new LinkedHashMap<>();
    private final LongSupplier clock;
    RecoveryCredentials() { this(System::currentTimeMillis); }
    RecoveryCredentials(LongSupplier clock) { this.clock = clock; }

    static final class Login implements AutoCloseable {
        final String username;
        final char[] password;
        Login(String username, char[] password) { this.username = username; this.password = password.clone(); }
        @Override public void close() { Arrays.fill(password, '\0'); }
    }
    private static final class Secret {
        final String username;
        final char[] password;
        final long expires;
        Secret(String username, String password, long expires) {
            this.username = username; this.password = password.toCharArray(); this.expires = expires;
        }
        void clear() { Arrays.fill(password, '\0'); }
    }
    synchronized void remember(String id, String username, String password, long expires) {
        purge(); forget(id);
        secrets.put(id, new Secret(username, password, expires));
    }
    synchronized void forget(String id) {
        Secret removed = secrets.remove(id);
        if (removed != null) removed.clear();
    }
    synchronized void purge() {
        var entries = secrets.entrySet().iterator();
        while (entries.hasNext()) {
            Secret secret = entries.next().getValue();
            if (clock.getAsLong() >= secret.expires) { secret.clear(); entries.remove(); }
        }
    }
    synchronized Login forUser(String username) throws IOException {
        purge();
        Secret latest = null;
        for (Secret secret : secrets.values()) if (secret.username.equals(username)) latest = secret;
        if (latest == null) throw new IOException("Sign in to WebAdmin again before approving FTPS recovery");
        return new Login(latest.username, latest.password);
    }
    @Override public synchronized void close() {
        secrets.values().forEach(Secret::clear); secrets.clear();
    }
}
