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
    void existingPendingCannotChangeApprovedCleanupOrDependencyProof() throws Exception {
        Files.createDirectory(gameDir.resolve("mods"));
        UpdatePlan.Operation install = new UpdatePlan.Operation("required", UpdatePlan.Kind.ADD,
                null, null, "required.jar", "a".repeat(128), 10);
        ClientDisablePlan.Entry cleanup = new ClientDisablePlan.Entry("serverextra", "Server Extra", "1",
                "server-extra.jar", "b".repeat(128), ClientCompatibilityReport.Kind.EXPLICIT_SERVER,
                List.of("serverextra"));
        ClientDisablePlan.DependencyGuard guard = new ClientDisablePlan.DependencyGuard(
                "server-extra.jar", cleanup.sha512());
        UpdatePlan approved = new UpdatePlan(ID, SERVER, STAMP, List.of(install), List.of(cleanup),
                List.of(guard), "c".repeat(128));
        Path pending = PendingStore.create(gameDir, approved);
        byte[] original = Files.readAllBytes(pending.resolve("pending.json"));

        List<UpdatePlan> substitutions = List.of(
                new UpdatePlan(ID, SERVER, "2026-09-18_02-30-16", List.of(install),
                        List.of(cleanup), List.of(guard), approved.stagedGraphFingerprint()),
                new UpdatePlan(ID, SERVER, STAMP, List.of(install),
                        List.of(new ClientDisablePlan.Entry("serverextra", "Changed name", "1",
                                cleanup.filename(), cleanup.sha512(), cleanup.kind(), cleanup.containedModIds())),
                        List.of(guard), approved.stagedGraphFingerprint()),
                new UpdatePlan(ID, SERVER, STAMP, List.of(install), List.of(cleanup),
                        List.of(guard, new ClientDisablePlan.DependencyGuard("retained.jar", "d".repeat(128))),
                        approved.stagedGraphFingerprint()),
                new UpdatePlan(ID, SERVER, STAMP, List.of(install), List.of(cleanup),
                        List.of(guard), "e".repeat(128)));
        for (UpdatePlan substitution : substitutions) {
            assertThrows(java.io.IOException.class, () -> PendingStore.create(gameDir, substitution));
            assertArrayEquals(original, Files.readAllBytes(pending.resolve("pending.json")));
        }
    }

    @Test
    void readyInstallOnlyPendingCanBePromotedOnceBeforeUpdaterStarts() throws Exception {
        Files.createDirectory(gameDir.resolve("mods"));
        UpdatePlan.Operation install = new UpdatePlan.Operation("required", UpdatePlan.Kind.ADD,
                null, null, "required.jar", "a".repeat(128), 10);
        UpdatePlan staged = new UpdatePlan(ID, SERVER, STAMP, List.of(install));
        Path pending = PendingStore.create(gameDir, staged);
        ClientDisablePlan.Entry cleanup = new ClientDisablePlan.Entry("serverextra", "Server Extra", "1",
                "server-extra.jar", "b".repeat(128), ClientCompatibilityReport.Kind.EXPLICIT_SERVER,
                List.of("serverextra"));
        ClientDisablePlan.DependencyGuard guard = new ClientDisablePlan.DependencyGuard(
                cleanup.filename(), cleanup.sha512());
        UpdatePlan combined = new UpdatePlan(ID, SERVER, STAMP, List.of(install), List.of(cleanup),
                List.of(guard), "c".repeat(128));

        assertEquals(pending, PendingStore.create(gameDir, combined));
        assertTrue(Files.readString(pending.resolve("pending.json")).contains("server-extra.jar"));
        assertThrows(java.io.IOException.class, () -> PendingStore.create(gameDir, staged));

        Path secondGame = Files.createDirectory(gameDir.resolve("other-game"));
        Files.createDirectory(secondGame.resolve("mods"));
        Path unstarted = PendingStore.create(secondGame, staged);
        Files.writeString(unstarted.resolve("update.log"), "updater started");
        assertThrows(java.io.IOException.class, () -> PendingStore.create(secondGame, staged));
        assertThrows(java.io.IOException.class, () -> PendingStore.create(secondGame, combined));
    }

    @Test
    void launchRejectsNewUnobservedJarAfterCleanupApproval() throws Exception {
        Path mods = Files.createDirectory(gameDir.resolve("mods"));
        Path extra = Files.writeString(mods.resolve("server-extra.jar"), "extra");
        String hash = Hashing.sha512(extra);
        ClientDisablePlan.Entry cleanup = new ClientDisablePlan.Entry("serverextra", "Server Extra", "1",
                "server-extra.jar", hash, ClientCompatibilityReport.Kind.EXPLICIT_SERVER,
                List.of("serverextra"));
        UpdatePlan plan = new UpdatePlan(ID, SERVER, STAMP, List.of(), List.of(cleanup),
                List.of(new ClientDisablePlan.DependencyGuard("server-extra.jar", hash)), null);
        PendingStore.create(gameDir, plan);
        assertDoesNotThrow(() -> PendingRecovery.verifyForLaunch(gameDir, plan));

        Files.writeString(mods.resolve("unknown.jar"), "new owner");
        assertThrows(java.io.IOException.class, () -> PendingRecovery.verifyForLaunch(gameDir, plan));
        assertEquals("extra", Files.readString(extra));
    }

    @Test
    void explicitRetryCanChangeOnlyTimestampOfUncompletedCleanup() throws Exception {
        Path mods = Files.createDirectory(gameDir.resolve("mods"));
        Path extra = Files.writeString(mods.resolve("server-extra.jar"), "extra");
        String hash = Hashing.sha512(extra);
        ClientDisablePlan.Entry cleanup = new ClientDisablePlan.Entry("serverextra", "Server Extra", "1",
                "server-extra.jar", hash, ClientCompatibilityReport.Kind.EXPLICIT_SERVER,
                List.of("serverextra"));
        UpdatePlan original = new UpdatePlan(ID, SERVER, STAMP, List.of(), List.of(cleanup),
                List.of(new ClientDisablePlan.DependencyGuard("server-extra.jar", hash)), null);
        Path pending = PendingStore.create(gameDir, original);
        Files.writeString(pending.resolve("failed.marker"), "FAILED");
        PendingRecovery.Item item = PendingRecovery.scan(gameDir).get(0);
        assertTrue(item.failed());
        assertThrows(java.io.IOException.class, () -> PendingStore.create(gameDir, original));

        UpdatePlan retry = PendingRecovery.prepareRetry(gameDir, item);
        assertNotEquals(original.timestamp(), retry.timestamp());
        assertEquals(original.cleanupEntries(), retry.cleanupEntries());
        assertEquals(original.dependencyGuards(), retry.dependencyGuards());
        assertFalse(Files.exists(pending.resolve("failed.marker")));
        assertDoesNotThrow(() -> PendingRecovery.verifyForLaunch(gameDir, retry));
        assertThrows(java.io.IOException.class, () -> PendingStore.create(gameDir, original));
    }

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

    @Test
    void verifiedUpdateKeepsOriginalUpdaterLogInBackup() throws Exception {
        Path mods = Files.createDirectory(gameDir.resolve("mods"));
        Path installed = Files.writeString(mods.resolve("new.jar"), "verified bytes");
        UpdatePlan plan = new UpdatePlan(ID, SERVER, STAMP, List.of(new UpdatePlan.Operation(
                "create", UpdatePlan.Kind.ADD, null, null, "new.jar",
                Hashing.sha512(installed), Files.size(installed))));
        Path pending = PendingStore.create(gameDir, plan);
        Path backup = PathSafety.janeDirectory(gameDir, "backups", SERVER, STAMP);
        Files.copy(pending.resolve("backup.json"), backup.resolve("backup.json"));
        Path staging = PathSafety.janeDirectory(gameDir, "staging", ID);
        Files.copy(installed, staging.resolve("new.jar"));
        Files.writeString(pending.resolve("update.log"), "original updater log\n");
        Files.writeString(pending.resolve("success.marker"), "SUCCESS\n");

        assertTrue(PendingRecovery.scan(gameDir).isEmpty());
        assertEquals("original updater log\n", Files.readString(backup.resolve("update.log")));
        assertFalse(Files.exists(pending));
    }

    @Test
    void unsafeUpdaterLogStopsCleanupAndKeepsPendingForReview() throws Exception {
        Path mods = Files.createDirectory(gameDir.resolve("mods"));
        Path installed = Files.writeString(mods.resolve("new.jar"), "verified bytes");
        UpdatePlan plan = new UpdatePlan(ID, SERVER, STAMP, List.of(new UpdatePlan.Operation(
                "create", UpdatePlan.Kind.ADD, null, null, "new.jar",
                Hashing.sha512(installed), Files.size(installed))));
        Path pending = PendingStore.create(gameDir, plan);
        Path backup = PathSafety.janeDirectory(gameDir, "backups", SERVER, STAMP);
        Files.copy(pending.resolve("backup.json"), backup.resolve("backup.json"));
        Files.copy(installed, PathSafety.janeDirectory(gameDir, "staging", ID).resolve("new.jar"));
        Files.createDirectory(pending.resolve("update.log"));
        Files.writeString(pending.resolve("success.marker"), "SUCCESS\n");

        var items = PendingRecovery.scan(gameDir);
        assertEquals(1, items.size());
        assertNotNull(items.get(0).issue());
        assertTrue(Files.isDirectory(pending.resolve("update.log")));
        assertFalse(Files.exists(backup.resolve("success.marker")));
    }
}
