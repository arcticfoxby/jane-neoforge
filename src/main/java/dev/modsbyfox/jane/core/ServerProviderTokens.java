package dev.modsbyfox.jane.core;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Memory-only, per-login capabilities. Never persist or log token values. */
public final class ServerProviderTokens {
    private static final long OFFER_LIFETIME = Duration.ofMinutes(60).toMillis();
    private static final long TRANSFER_IDLE_LIFETIME = Duration.ofMinutes(15).toMillis();
    private static final int MAX_SESSIONS = 256;
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    // Insertion order lets issue() evict the oldest unused offer without disturbing active transfers.
    private final Map<String, Session> sessions = new LinkedHashMap<>();
    private record Session(Set<String> hashes, long expiresAt, int requests, boolean active, boolean used) { }

    public ServerProviderTokens(Clock clock) { this.clock = clock; }

    public synchronized String issue(Set<String> hashes) {
        sessions.entrySet().removeIf(entry -> !entry.getValue().active()
                && entry.getValue().expiresAt() <= clock.millis());
        if (sessions.size() >= MAX_SESSIONS) {
            String oldestUnused = sessions.entrySet().stream()
                    .filter(entry -> !entry.getValue().active() && !entry.getValue().used())
                    .map(Map.Entry::getKey).findFirst().orElse(null);
            if (oldestUnused == null) throw new IllegalStateException("Too many provider sessions");
            sessions.remove(oldestUnused);
        }
        byte[] bytes = new byte[32];
        String token;
        do {
            random.nextBytes(bytes);
            token = java.util.HexFormat.of().formatHex(bytes);
        } while (sessions.containsKey(token));
        sessions.put(token, new Session(Set.copyOf(hashes), clock.millis() + OFFER_LIFETIME, 0, false, false));
        return token;
    }

    /** Acquires the one concurrent transfer allowed for this token. */
    public synchronized boolean acquire(String token, String hash) {
        Session session = sessions.get(token);
        if (session == null) return false;
        if (session.expiresAt() <= clock.millis()) {
            if (!session.active()) sessions.remove(token);
            return false;
        }
        if (!session.hashes().contains(hash) || session.active() || session.requests() >= 256) return false;
        sessions.put(token, new Session(session.hashes(), clock.millis() + TRANSFER_IDLE_LIFETIME,
                session.requests() + 1, true, true));
        return true;
    }

    public synchronized boolean known(String token) {
        Session session = sessions.get(token);
        if (session == null) return false;
        if (session.expiresAt() > clock.millis()) return true;
        if (!session.active()) sessions.remove(token);
        return false;
    }

    public synchronized void release(String token) {
        Session session = sessions.get(token);
        if (session != null) sessions.put(token,
                new Session(session.hashes(), session.expiresAt(), session.requests(), false, session.used()));
    }

    public synchronized void clear() { sessions.clear(); }
}
