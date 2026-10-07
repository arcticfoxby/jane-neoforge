package dev.modsbyfox.jane.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PendingRecoveryTest {
    @TempDir Path gameDir;
    private static final String ID = "12345678-1234-1234-1234-123456789abc";
    private static final String SERVER = "b".repeat(64);
    private static final String OTHER = "c".repeat(64);
    private static final String STAMP = "2026-09-18_02-30-15";

    @Test
    void incompletePendingRequiresExplicitRetryAndReverification() throws Exception {
        Files.createDirectory(gameDir.resolve("mods"));
        Path stage = PathSafety.janeDirectory(gameDir, "staging", ID);
        Path file = Files.writeString(stage.resolve("new.jar"), "new");
        UpdatePlan plan = new UpdatePlan(ID, SERVER, STAMP, List.of(
                new UpdatePlan.Operation("create", UpdatePlan.Kind.ADD, null, null, "new.jar", Hashing.sha512(file), Files.size(file))));
        PendingStore.create(gameDir, plan);
        assertTrue(Files.readString(gameDir.resolve("jane/pending/" + ID + "/backup.json")).contains("\"oldFile\": null"));
        var items = PendingRecovery.scan(gameDir);
        assertEquals(1, items.size());
        assertFalse(items.get(0).failed());
        assertEquals(plan, items.get(0).plan());
        assertDoesNotThrow(() -> PendingRecovery.verifyForLaunch(gameDir, plan));
        Files.writeString(file, "tampered");
        assertThrows(java.io.IOException.class, () -> PendingRecovery.prepareRetry(gameDir, items.get(0)));
        PendingRecovery.abandon(gameDir, items.get(0));
        assertFalse(Files.exists(gameDir.resolve("jane/pending/" + ID)));
        assertFalse(Files.exists(gameDir.resolve("jane/staging/" + ID)));
    }

    @Test
    void unicodeOldFileRoundTripsAndVerifiesThroughPendingAndBackup() throws Exception {
        Path mods = Files.createDirectory(gameDir.resolve("mods"));
        String oldName = "【农夫乐事】farmers-delight-fabric-1.4.3.jar";
        Path old = Files.writeString(mods.resolve(oldName), "old farmers delight");
        Path staging = PathSafety.janeDirectory(gameDir, "staging", ID);
        Path fresh = Files.writeString(staging.resolve("farmers-delight-refabricated-2.5.4.jar"), "new farmers delight");
        UpdatePlan plan = new UpdatePlan(ID, SERVER, STAMP, List.of(new UpdatePlan.Operation(
                "farmersdelight", UpdatePlan.Kind.REPLACE, oldName, Hashing.sha512(old),
                fresh.getFileName().toString(), Hashing.sha512(fresh), Files.size(fresh))));
        Path pending = PendingStore.create(gameDir, plan);
        assertTrue(Files.readString(pending.resolve("pending.json")).contains(oldName));
        UpdatePlan restored = PendingRecovery.scan(gameDir).get(0).plan();
        assertEquals(oldName, restored.operations().get(0).oldFile());
        assertDoesNotThrow(() -> PendingRecovery.verifyForLaunch(gameDir, restored));

        Path backup = PathSafety.janeDirectory(gameDir, "backups", SERVER, STAMP);
        Files.copy(pending.resolve("backup.json"), backup.resolve("backup.json"));
        Files.move(old, backup.resolve(oldName));
        Files.copy(fresh, mods.resolve(fresh.getFileName()));
        Files.writeString(pending.resolve("success.marker"), "SUCCESS");
        assertTrue(PendingRecovery.scan(gameDir).isEmpty());
        assertTrue(Files.exists(backup.resolve("success.marker")));
        assertFalse(Files.exists(pending));
    }

    @Test
    void successfulPendingIsVerifiedThenOnlyOldestSuccessfulBackupForThatServerIsRemoved() throws Exception {
        Path mods = Files.createDirectory(gameDir.resolve("mods"));
        Path newJar = Files.writeString(mods.resolve("new.jar"), "new");
        UpdatePlan plan = new UpdatePlan(ID, SERVER, STAMP, List.of(
                new UpdatePlan.Operation("create", UpdatePlan.Kind.ADD, null, null, "new.jar", Hashing.sha512(newJar), Files.size(newJar))));
        Path pending = PendingStore.create(gameDir, plan);
        Path stage = PathSafety.janeDirectory(gameDir, "staging", ID);
        Files.writeString(stage.resolve("new.jar"), "new");
        Path backups = PathSafety.janeDirectory(gameDir, "backups", SERVER);
        String oldest = "2026-09-01_00-00-01";
        for (int i = 1; i <= 5; i++) {
            String stamp = "2026-09-0" + i + "_00-00-01";
            Path prior = PathSafety.janeDirectory(gameDir, "backups", SERVER, stamp);
            Files.writeString(prior.resolve("backup.json"), "{}");
            Files.writeString(prior.resolve("success.marker"), "SUCCESS");
        }
        Path failed = PathSafety.janeDirectory(gameDir, "backups", SERVER, "2026-09-06_00-00-01");
        Files.writeString(failed.resolve("backup.json"), "{}");
        Path other = PathSafety.janeDirectory(gameDir, "backups", OTHER, oldest);
        Files.writeString(other.resolve("success.marker"), "SUCCESS");
        Path current = PathSafety.janeDirectory(gameDir, "backups", SERVER, STAMP);
        Files.copy(pending.resolve("backup.json"), current.resolve("backup.json"));
        Files.writeString(pending.resolve("success.marker"), "SUCCESS");

        assertTrue(PendingRecovery.scan(gameDir).isEmpty());
        assertTrue(Files.isRegularFile(current.resolve("success.marker")));
        assertFalse(Files.exists(backups.resolve(oldest)));
        assertTrue(Files.exists(failed));
        assertTrue(Files.exists(other));
        assertFalse(Files.exists(pending));
        assertFalse(Files.exists(stage));
    }
}
