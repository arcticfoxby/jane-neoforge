package dev.modsbyfox.jane.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SyncDecisionAuditTest {
    @TempDir Path game;

    @Test void writesShortDigestAndRejectsArbitraryFields() throws Exception {
        SyncDecisionAudit.record(game, "1.1.8.3-beta", "DIRECT_JOIN", Map.of(
                "serverId", "a".repeat(64), "requiredHash", "B".repeat(12),
                "modId", "example", "status", "USER_OVERRIDE"));
        String log = Files.readString(game.resolve("jane/logs/sync-decisions.log"));
        assertTrue(log.contains("serverId=" + "a".repeat(12)));
        assertTrue(log.contains("[Jane 1.1.8.3-beta][CLIENT] DIRECT_JOIN"));
        assertTrue(log.contains("requiredHash=" + "b".repeat(12)));
        assertFalse(log.contains("a".repeat(64)));
        assertThrows(IOException.class, () -> SyncDecisionAudit.record(game, "BAD EVENT",
                Map.of("status", "PASS")));
        assertThrows(IOException.class, () -> SyncDecisionAudit.record(game, "DIRECT_JOIN",
                Map.of("secret", "should-not-be-logged")));
        assertThrows(IOException.class, () -> SyncDecisionAudit.record(game, "bad version",
                "DIRECT_JOIN", Map.of("status", "PASS")));
    }

    @Test void unsafeLogDestinationDoesNotReceiveAuditData() throws Exception {
        Path logs = Files.createDirectories(game.resolve("jane/logs"));
        Files.createDirectory(logs.resolve("sync-decisions.log"));
        assertThrows(IOException.class, () -> SyncDecisionAudit.record(game, "DIRECT_JOIN",
                Map.of("status", "USER_OVERRIDE")));
    }

    @Test void realExtraSelectionAndCleanupEventsAcceptOnlySanitizedFields() throws Exception {
        String serverId = "c".repeat(64);
        String digest = "d".repeat(128);
        SyncDecisionAudit.record(game, "1.1.8.6-beta", "EXTRA_SERVER_DETECTED", Map.of(
                "serverId", serverId, "manifestDigest", digest,
                "candidateCount", "3", "verifiedCount", "2",
                "defaultSelectedCount", "1", "blockedCount", "1"));
        SyncDecisionAudit.record(game, "1.1.8.6-beta", "USER_SELECT_EXTRA_DISABLE", Map.of(
                "serverId", serverId, "manifestDigest", digest,
                "modId", "universal", "kind", "OTHER_EXTRA", "selectedCount", "1"));
        SyncDecisionAudit.record(game, "1.1.8.6-beta", "EXTRA_OTHER_SELECTED", Map.of(
                "serverId", serverId, "manifestDigest", digest,
                "filename", "universal.jar", "selectedCount", "1"));
        SyncDecisionAudit.record(game, "1.1.8.6-beta", "CLIENT_UPDATE_CONFIRMED", Map.of(
                "serverId", serverId, "manifestDigest", digest,
                "installCount", "2", "cleanupCount", "1"));
        String log = Files.readString(game.resolve("jane/logs/sync-decisions.log"));
        assertTrue(log.contains("kind=OTHER_EXTRA"));
        assertTrue(log.contains("filename=universal.jar"));
        assertTrue(log.contains("candidateCount=3"));
        assertTrue(log.contains("installCount=2"));
        assertFalse(log.contains(serverId));
        assertFalse(log.contains(digest));

        SyncDecisionAudit.record(game, "EXTRA_OTHER_SELECTED", Map.of("filename", "../secret.jar"));
        SyncDecisionAudit.record(game, "USER_SELECT_EXTRA_DISABLE", Map.of("kind", "NOT_A_KIND"));
        SyncDecisionAudit.record(game, "CLIENT_UPDATE_CONFIRMED", Map.of("cleanupCount", "nan"));
        log = Files.readString(game.resolve("jane/logs/sync-decisions.log"));
        assertTrue(log.contains("filename=[REDACTED]"));
        assertTrue(log.contains("kind=[REDACTED]"));
        assertTrue(log.contains("cleanupCount=[REDACTED]"));
        assertThrows(IOException.class, () -> SyncDecisionAudit.record(game,
                "CLIENT_UPDATE_CONFIRMED", Map.of("userToken", "secret")));
    }
}
