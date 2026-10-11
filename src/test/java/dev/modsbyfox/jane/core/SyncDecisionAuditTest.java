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
}
