package dev.modsbyfox.jane.core;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ServerProviderTokensTest {
    private static final String A = "a".repeat(128), B = "b".repeat(128);
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration duration) { now = now.plus(duration); }
    }

    @Test void unusedOfferLastsAnHourThenExpires() {
        MutableClock clock = new MutableClock();
        ServerProviderTokens tokens = new ServerProviderTokens(clock);
        String first = tokens.issue(Set.of(A));
        String second = tokens.issue(Set.of(A));
        assertTrue(first.matches("[0-9a-f]{64}"));
        assertNotEquals(first, second);
        assertFalse(tokens.acquire("0".repeat(64), A));
        assertFalse(tokens.acquire(first, B));
        clock.advance(Duration.ofMinutes(59));
        assertTrue(tokens.known(first));
        assertTrue(tokens.acquire(first, A));
        tokens.release(first);
        clock.advance(Duration.ofMinutes(2));
        assertFalse(tokens.known(second));
        assertFalse(tokens.acquire(second, A));
        tokens.clear();
    }

    @Test void successfulAcquiresRefreshTransferIdleLifetime() {
        MutableClock clock = new MutableClock();
        ServerProviderTokens tokens = new ServerProviderTokens(clock);
        String token = tokens.issue(Set.of(A));
        assertTrue(tokens.acquire(token, A));
        assertFalse(tokens.acquire(token, A));
        tokens.release(token);
        clock.advance(Duration.ofMinutes(14));
        assertTrue(tokens.acquire(token, A));
        tokens.release(token);
        clock.advance(Duration.ofMinutes(14));
        assertTrue(tokens.acquire(token, A));
        tokens.release(token);
        clock.advance(Duration.ofMinutes(16));
        assertFalse(tokens.acquire(token, A));
    }

    @Test void activeTransferSurvivesExpiryButCannotStartAnother() {
        MutableClock clock = new MutableClock();
        ServerProviderTokens tokens = new ServerProviderTokens(clock);
        String token = tokens.issue(Set.of(A));
        assertTrue(tokens.acquire(token, A));
        clock.advance(Duration.ofMinutes(16));
        assertFalse(tokens.acquire(token, A));
        tokens.release(token);
        assertFalse(tokens.acquire(token, A));
    }

    @Test void capacityEvictsOldestUnusedOfferButKeepsActiveSession() {
        MutableClock clock = new MutableClock();
        ServerProviderTokens tokens = new ServerProviderTokens(clock);
        String active = tokens.issue(Set.of(A));
        assertTrue(tokens.acquire(active, A));
        String oldestUnused = tokens.issue(Set.of(A));
        List<String> later = new ArrayList<>();
        for (int i = 2; i < 256; i++) later.add(tokens.issue(Set.of(A)));
        String replacement = tokens.issue(Set.of(A));
        assertFalse(tokens.known(oldestUnused));
        assertTrue(tokens.known(active));
        assertTrue(tokens.known(later.get(0)));
        assertTrue(tokens.known(replacement));
        tokens.release(active);
    }

    @Test void fullSetOfUsedSessionsFailsClosedAndExpiredSessionsAreCleaned() {
        MutableClock clock = new MutableClock();
        ServerProviderTokens tokens = new ServerProviderTokens(clock);
        for (int i = 0; i < 256; i++) {
            String token = tokens.issue(Set.of(A));
            assertTrue(tokens.acquire(token, A));
            tokens.release(token);
        }
        assertThrows(IllegalStateException.class, () -> tokens.issue(Set.of(A)));
        clock.advance(Duration.ofMinutes(16));
        String fresh = tokens.issue(Set.of(B));
        assertFalse(tokens.acquire(fresh, A));
        assertTrue(tokens.acquire(fresh, B));
    }
}
