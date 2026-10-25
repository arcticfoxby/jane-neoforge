package dev.modsbyfox.jane.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BackupTimestampTest {
    @TempDir Path gameDir;

    @Test
    void retrySkipsOccupiedCleanupAndInstallBackupTargets() throws Exception {
        String serverId = "a".repeat(64);
        LocalDateTime now = LocalDateTime.of(2026, 10, 26, 12, 0, 0);
        Files.createDirectory(PathSafety.janeDirectory(gameDir, "disabled", serverId)
                .resolve("2026-10-26_12-00-00"));
        Files.createDirectory(PathSafety.janeDirectory(gameDir, "backups", serverId)
                .resolve("2026-10-26_12-00-01"));

        assertEquals("2026-10-26_12-00-02", BackupTimestamp.next(gameDir, serverId, now));
    }
}
