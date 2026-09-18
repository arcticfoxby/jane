package dev.modsbyfox.jane.core;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Memory-only, per-login capabilities. Never persist or log token values. */
public final class ServerProviderTokens {
    private static final long LIFETIME = Duration.ofMinutes(10).toMillis();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    private final Map<String, Session> sessions = new HashMap<>();
    private record Session(Set<String> hashes, long expiresAt, int requests, boolean active) { }

    public ServerProviderTokens(Clock clock) { this.clock = clock; }

    public synchronized String issue(Set<String> hashes) {
        sessions.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= clock.millis());
        if (sessions.size() >= 256) throw new IllegalStateException("Too many provider sessions");
        byte[] bytes = new byte[32];
        String token;
        do {
            random.nextBytes(bytes);
            token = java.util.HexFormat.of().formatHex(bytes);
        } while (sessions.containsKey(token));
        sessions.put(token, new Session(Set.copyOf(hashes), clock.millis() + LIFETIME, 0, false));
        return token;
    }

    /** Acquires the one concurrent transfer allowed for this token. */
    public synchronized boolean acquire(String token, String hash) {
        Session session = sessions.get(token);
        if (session == null || session.expiresAt() <= clock.millis()) {
            sessions.remove(token);
            return false;
        }
        if (!session.hashes().contains(hash) || session.active() || session.requests() >= 256) return false;
        sessions.put(token, new Session(session.hashes(), session.expiresAt(), session.requests() + 1, true));
        return true;
    }

    public synchronized boolean known(String token) {
        Session session = sessions.get(token);
        return session != null && session.expiresAt() > clock.millis();
    }

    public synchronized void release(String token) {
        Session session = sessions.get(token);
        if (session != null) sessions.put(token,
                new Session(session.hashes(), session.expiresAt(), session.requests(), false));
    }

    public synchronized void clear() { sessions.clear(); }
}
