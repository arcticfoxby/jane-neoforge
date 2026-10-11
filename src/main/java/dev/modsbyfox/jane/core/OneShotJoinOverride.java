package dev.modsbyfox.jane.core;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** A short-lived, single-use local permission to report an explicit Required-file risk. */
public final class OneShotJoinOverride {
    private static final Duration LIFETIME = Duration.ofSeconds(90);
    private final Clock clock;
    private final Object clientSession = new Object();
    private Intent intent;
    private Object connection;

    private record Intent(String serverId, String manifestDigest, String selectionDigest,
                          Instant selectionTimestamp, long selectionRevision, SyncSelection selection,
                          String assessmentDigest, Instant issuedAt, Object clientSession) { }

    public OneShotJoinOverride() { this(Clock.systemUTC()); }
    public OneShotJoinOverride(Clock clock) { this.clock = Objects.requireNonNull(clock, "clock"); }

    /** The provider offer and its token do not affect the Required-file identity. */
    public static String manifestDigest(RequiredManifest manifest) {
        Objects.requireNonNull(manifest, "manifest");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(ManifestCodec.encode(manifest)));
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalArgumentException("Cannot digest Jane manifest", exception);
        }
    }

    /** Reject file errors, incomplete results, and ambiguous unmatched physical JARs. */
    public static boolean overrideable(RequiredManifest manifest, List<Comparison.Result> results) {
        if (manifest == null || results == null || results.size() != manifest.entries().size()) return false;
        boolean hasMismatch = false;
        for (int i = 0; i < results.size(); i++) {
            Comparison.Result result = results.get(i);
            if (result == null || !manifest.entries().get(i).equals(result.required())
                    || result.status() == null || result.localIssue() != null) return false;
            if (result.status() != Comparison.Status.OK && !result.retainedCandidates().isEmpty()) return false;
            switch (result.status()) {
                case OK -> {
                    if (result.local() == null || !result.required().sha512().equals(result.localHash())) return false;
                }
                case MISSING -> {
                    if (result.local() != null || result.localHash() != null) return false;
                    hasMismatch = true;
                }
                case VERSION_MISMATCH, HASH_MISMATCH -> {
                    if (result.local() == null || result.localHash() == null
                            || !result.localHash().matches("[0-9a-f]{128}")) return false;
                    hasMismatch = true;
                }
                case FILE_ERROR -> { return false; }
            }
        }
        return hasMismatch;
    }

    public synchronized boolean arm(String serverId, RequiredManifest manifest, SyncSelection selection,
                                    List<Comparison.Result> results) {
        clear();
        if (!validServer(serverId) || selection == null || !overrideable(manifest, results)) return false;
        try {
            String manifestHash = manifestDigest(manifest);
            synchronized (selection) {
                String choicesHash = selection.digest();
                if (!serverId.equals(selection.serverId()) || !manifestHash.equals(selection.manifestDigest())
                        || !choicesHash.matches("[0-9a-f]{64}")
                        || !allMismatchesDeselected(selection, results)) return false;
                intent = new Intent(serverId, manifestHash, choicesHash,
                        selection.selectionTimestamp(), selection.revision(), selection,
                        assessmentDigest(results), clock.instant(), clientSession);
            }
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public synchronized void beginConnection(Object nextConnection) {
        if (intent == null) return;
        if (nextConnection == null || expired()) { clear(); return; }
        if (connection == null) connection = nextConnection;
        else if (connection != nextConnection) clear();
    }

    /** Consume on any result so a failed recheck cannot be retried without a new confirmation. */
    public synchronized boolean consume(Object currentConnection, String serverId, RequiredManifest manifest,
                                        List<Comparison.Result> results) {
        try {
            Intent saved = intent;
            return saved != null && currentConnection != null && connection == currentConnection
                    && !expired() && saved.clientSession() == clientSession
                    && saved.serverId().equals(serverId)
                    && saved.manifestDigest().equals(manifestDigest(manifest))
                    && selectionUnchanged(saved, serverId, results)
                    && overrideable(manifest, results)
                    && saved.assessmentDigest().equals(assessmentDigest(results));
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

    private boolean expired() {
        Duration age = Duration.between(intent.issuedAt(), clock.instant());
        return age.isNegative() || age.compareTo(LIFETIME) > 0;
    }

    private static boolean validServer(String serverId) {
        return serverId != null && serverId.matches("[0-9a-f]{64}");
    }

    private static boolean selectionUnchanged(Intent saved, String serverId, List<Comparison.Result> results) {
        SyncSelection selection = saved.selection();
        synchronized (selection) {
            return selection.serverId().equals(serverId)
                    && selection.manifestDigest().equals(saved.manifestDigest())
                    && selection.selectionTimestamp().equals(saved.selectionTimestamp())
                    && selection.revision() == saved.selectionRevision()
                    && selection.digest().equals(saved.selectionDigest())
                    && allMismatchesDeselected(selection, results);
        }
    }

    private static boolean allMismatchesDeselected(SyncSelection selection, List<Comparison.Result> results) {
        for (Comparison.Result result : results) {
            if (result.status() != Comparison.Status.OK && selection.isSelected(result.required().modId())) return false;
        }
        return true;
    }

    private static String assessmentDigest(List<Comparison.Result> results) {
        StringBuilder value = new StringBuilder();
        for (Comparison.Result result : results) {
            append(value, result.required().modId());
            append(value, result.status().name());
            Comparison.LocalMod local = result.local();
            append(value, local == null ? null : local.modId());
            append(value, local == null ? null : local.version());
            append(value, local == null ? null : local.jar().toString());
            append(value, result.localHash());
            value.append(result.retainedCandidates().size()).append(':');
            for (Comparison.LocalMod candidate : result.retainedCandidates()) {
                append(value, candidate.modId());
                append(value, candidate.version());
                append(value, candidate.jar().toString());
            }
        }
        return Hashing.sha256(value.toString());
    }

    private static void append(StringBuilder value, String field) {
        if (field == null) value.append("-1:");
        else value.append(field.length()).append(':').append(field);
    }
}
