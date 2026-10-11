package dev.modsbyfox.jane.core;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Local permission to preserve extra Mods on one reconnect when Required files already match. */
public final class OneShotExtraJoin {
    private static final Duration LIFETIME = Duration.ofSeconds(90);
    private final Clock clock;
    private Intent intent;
    private Object connection;

    private record Intent(String serverId, String manifestDigest, String extrasDigest, Instant issuedAt) { }

    public OneShotExtraJoin() { this(Clock.systemUTC()); }
    public OneShotExtraJoin(Clock clock) { this.clock = Objects.requireNonNull(clock, "clock"); }

    public synchronized boolean arm(String serverId, RequiredManifest manifest, ClientCompatibilityReport extras) {
        clear();
        if (serverId == null || !serverId.matches("[0-9a-f]{64}") || manifest == null || !hasExtras(extras))
            return false;
        intent = new Intent(serverId, OneShotJoinOverride.manifestDigest(manifest), digest(extras), clock.instant());
        return true;
    }

    public synchronized void beginConnection(Object nextConnection) {
        if (intent == null) return;
        if (nextConnection == null || expired()) { clear(); return; }
        if (connection == null) connection = nextConnection;
        else if (connection != nextConnection) clear();
    }

    public synchronized boolean consume(Object currentConnection, String serverId, RequiredManifest manifest,
                                        ClientCompatibilityReport extras) {
        try {
            return intent != null && currentConnection != null && connection == currentConnection
                    && !expired() && intent.serverId().equals(serverId)
                    && intent.manifestDigest().equals(OneShotJoinOverride.manifestDigest(manifest))
                    && hasExtras(extras) && intent.extrasDigest().equals(digest(extras));
        } catch (RuntimeException exception) {
            return false;
        } finally {
            clear();
        }
    }

    public synchronized void clearForConnection(Object currentConnection) {
        if (currentConnection != null && connection == currentConnection) clear();
    }

    public synchronized void clear() { intent = null; connection = null; }

    public static boolean hasExtras(ClientCompatibilityReport report) {
        return report != null && (!report.explicitClientMods().isEmpty() || !report.otherExtraMods().isEmpty());
    }

    /** Use observed metadata; displaying universal extras does not require hashing them. */
    public static String digest(ClientCompatibilityReport report) {
        Objects.requireNonNull(report, "report");
        StringBuilder data = new StringBuilder();
        for (var mod : report.explicitClientMods()) append(data, mod);
        data.append('|');
        for (var mod : report.otherExtraMods()) append(data, mod);
        return Hashing.sha256(data.toString());
    }

    private boolean expired() {
        Duration age = Duration.between(intent.issuedAt(), clock.instant());
        return age.isNegative() || age.compareTo(LIFETIME) > 0;
    }

    private static void append(StringBuilder data, ClientCompatibilityReport.ExtraMod mod) {
        for (String field : new String[] { mod.kind().name(), mod.modId(), mod.version(),
                mod.filename(), mod.sha512() }) {
            if (field == null) data.append("-1:");
            else data.append(field.length()).append(':').append(field);
        }
    }
}
