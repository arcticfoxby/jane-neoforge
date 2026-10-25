package dev.modsbyfox.jane.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** Real CMD tests for one INSTALL+CLEANUP transaction after the client process exits. */
@EnabledOnOs(OS.WINDOWS)
class UnifiedClientCleanupIntegrationTest {
    private static final String SERVER = "c".repeat(64);
    private static final String STAMP = "2026-10-26_12-00-00";
    @TempDir Path root;
    private Path game;
    private String output;

    @BeforeEach void createGame() throws Exception {
        game = Files.createDirectory(root.resolve("中文 & % ^ ( ) !"));
        Files.createDirectory(game.resolve("mods"));
    }

    @Test void cleanupOnlyMovesExactJarAndKeepsRecoveryRecord() throws Exception {
        Path extra = Files.writeString(game.resolve("mods/server-tool.jar"), "server tool");
        UpdatePlan plan = plan(List.of(), List.of(cleanup(extra, "servertool")));
        Path script = prepare(plan);
        assertEquals(0, run(script), output);
        Path disabled = disabled(plan);
        assertFalse(Files.exists(extra));
        assertEquals("server tool", Files.readString(disabled.resolve("server-tool.jar")));
        assertTrue(Files.isRegularFile(disabled.resolve("disabled.json")));
        assertTrue(Files.isRegularFile(disabled.resolve("disable.log")));
        assertTrue(Files.isRegularFile(disabled.resolve("success.marker")));
        assertTrue(Files.isRegularFile(pending(plan).resolve("success.marker")));
        assertTrue(Files.readString(disabled.resolve("disabled.json")).contains("EXPLICIT_SERVER"));
        assertTrue(Files.readString(disabled.resolve("disable.log")).contains("CLEANUP servertool OK"));
    }

    @Test void unicodePhysicalJarIsMovedWithoutChangingItsName() throws Exception {
        String filename = "【测试】服务端管理工具.jar";
        Path extra = Files.writeString(game.resolve("mods").resolve(filename), "server tool");
        UpdatePlan plan = plan(List.of(), List.of(cleanup(extra, "servertool")));

        assertEquals(0, run(prepare(plan)), output);
        assertFalse(Files.exists(extra));
        assertEquals("server tool", Files.readString(disabled(plan).resolve(filename)));
        assertTrue(Files.readString(disabled(plan).resolve("disabled.json")).contains(filename));
    }

    @Test
    void installationAndCleanupSucceedInOneScript() throws Exception {
        Path extra = Files.writeString(game.resolve("mods/server-tool.jar"), "server tool");
        Path staged = stage("new.jar", "new required");
        UpdatePlan plan = plan(List.of(add(staged, "required")), List.of(cleanup(extra, "servertool")));
        assertEquals(0, run(prepare(plan)), output);
        assertFalse(Files.exists(extra));
        assertEquals("new required", Files.readString(game.resolve("mods/new.jar")));
        assertEquals("server tool", Files.readString(disabled(plan).resolve("server-tool.jar")));
        assertTrue(Files.isRegularFile(pending(plan).resolve("success.marker")));
    }

    @Test
    void failedInstallRollsBackInstalledJarAndCleanup() throws Exception {
        Path extra = Files.writeString(game.resolve("mods/server-tool.jar"), "server tool");
        Path first = stage("first.jar", "first required");
        Path second = stage("second.jar", "second required");
        UpdatePlan plan = plan(List.of(add(first, "first"), add(second, "second")),
                List.of(cleanup(extra, "servertool")));
        Path script = prepare(plan);
        String original = Files.readString(script);
        String check = "call :verify_jar \"jane\\staging\\" + plan.syncId() + "\\second.jar\"";
        int secondCheck = original.lastIndexOf(check);
        assertTrue(secondCheck > original.indexOf(check));
        Files.writeString(script, original.substring(0, secondCheck)
                + "> \"jane\\staging\\" + plan.syncId() + "\\second.jar\" echo tampered\r\n"
                + original.substring(secondCheck), StandardCharsets.UTF_8);
        assertNotEquals(0, run(script), output);
        assertEquals("server tool", Files.readString(extra));
        assertFalse(Files.exists(game.resolve("mods/first.jar")));
        assertFalse(Files.exists(game.resolve("mods/second.jar")));
        assertFalse(Files.exists(pending(plan).resolve("success.marker")));
        assertTrue(Files.isRegularFile(pending(plan).resolve("failed.marker")));
        assertTrue(Files.readString(pending(plan).resolve("update.log"))
                .contains("ROLLBACK CLEANUP servertool OK"));
    }

    @Test void secondCleanupMoveFailureRestoresFirst() throws Exception {
        Path first = Files.writeString(game.resolve("mods/first.jar"), "first extra");
        Path second = Files.writeString(game.resolve("mods/second.jar"), "second extra");
        UpdatePlan plan = plan(List.of(), List.of(cleanup(first, "first"), cleanup(second, "second")));
        Path script = prepare(plan);
        String original = Files.readString(script);
        String check = "call :target_absent \"jane\\disabled\\" + SERVER + "\\" + STAMP + "\\second.jar\"";
        int last = original.lastIndexOf(check);
        assertTrue(last > original.indexOf(check));
        String injected = original.substring(0, last)
                + "> \"jane\\disabled\\" + SERVER + "\\" + STAMP + "\\second.jar\" echo collision\r\n"
                + original.substring(last);
        Files.writeString(script, injected, StandardCharsets.UTF_8);
        assertNotEquals(0, run(script), output);
        assertEquals("first extra", Files.readString(first));
        assertEquals("second extra", Files.readString(second));
        assertFalse(Files.exists(pending(plan).resolve("success.marker")));
        assertTrue(Files.readString(pending(plan).resolve("update.log"))
                .contains("ROLLBACK CLEANUP first OK"));
    }

    @Test
    void rollbackFailureIsExplicitAndLeavesOriginalBackup() throws Exception {
        Path extra = Files.writeString(game.resolve("mods/server-tool.jar"), "server tool");
        Path staged = stage("new.jar", "new required");
        UpdatePlan plan = plan(List.of(add(staged, "required")),
                List.of(cleanup(extra, "servertool")));
        Path script = prepare(plan);
        String original = Files.readString(script);
        String check = "call :verify_jar \"jane\\staging\\" + plan.syncId() + "\\new.jar\"";
        int index = original.lastIndexOf(check);
        assertTrue(index > original.indexOf(check));
        Files.writeString(script, original.substring(0, index)
                + "> \"mods\\server-tool.jar\" echo collision\r\n"
                + "> \"jane\\staging\\" + plan.syncId() + "\\new.jar\" echo tampered\r\n"
                + original.substring(index), StandardCharsets.UTF_8);
        assertNotEquals(0, run(script), output);
        assertTrue(output.contains("回滚未完成"), output);
        assertEquals("server tool", Files.readString(disabled(plan).resolve("server-tool.jar")));
        assertTrue(Files.isRegularFile(pending(plan).resolve("failed.marker")));
        assertFalse(Files.exists(pending(plan).resolve("success.marker")));
    }

    @Test void changedCleanupFileAndOccupiedBackupFailBeforeMove() throws Exception {
        Path extra = Files.writeString(game.resolve("mods/server-tool.jar"), "server tool");
        UpdatePlan plan = plan(List.of(), List.of(cleanup(extra, "servertool")));
        Path script = prepare(plan);
        Files.writeString(extra, "changed after confirmation");
        assertNotEquals(0, run(script), output);
        assertEquals("changed after confirmation", Files.readString(extra));
        assertFalse(Files.exists(disabled(plan).resolve("server-tool.jar")));
        assertFalse(Files.exists(pending(plan).resolve("success.marker")));
    }

    @Test void changedStagedJarFailsBeforeAnyCleanupMove() throws Exception {
        Path extra = Files.writeString(game.resolve("mods/server-tool.jar"), "server tool");
        Path staged = stage("new.jar", "new required");
        UpdatePlan plan = plan(List.of(add(staged, "required")), List.of(cleanup(extra, "servertool")));
        Path script = prepare(plan);
        Files.writeString(game.resolve("jane/staging").resolve(plan.syncId()).resolve("new.jar"), "tampered");

        assertNotEquals(0, run(script), output);
        assertEquals("server tool", Files.readString(extra));
        assertFalse(Files.exists(disabled(plan).resolve("server-tool.jar")));
        assertFalse(Files.readString(pending(plan).resolve("update.log")).contains("CLEANUP servertool OK"));
    }

    @Test void newUnknownJarAfterConfirmationBlocksEveryMove() throws Exception {
        Path extra = Files.writeString(game.resolve("mods/server-tool.jar"), "server tool");
        UpdatePlan plan = plan(List.of(), List.of(cleanup(extra, "servertool")));
        Path script = prepare(plan);
        Path unknown = Files.writeString(game.resolve("mods/new-unknown.jar"), "unknown owner");

        assertNotEquals(0, run(script), output);
        assertEquals("server tool", Files.readString(extra));
        assertEquals("unknown owner", Files.readString(unknown));
        assertFalse(Files.exists(disabled(plan).resolve("server-tool.jar")));
        assertFalse(Files.isRegularFile(pending(plan).resolve("success.marker")));
    }

    @Test void pendingMarkerFailureRemovesDisabledSuccessMarkerAndRestoresJar() throws Exception {
        Path extra = Files.writeString(game.resolve("mods/server-tool.jar"), "server tool");
        UpdatePlan plan = plan(List.of(), List.of(cleanup(extra, "servertool")));
        Path script = prepare(plan);
        String original = Files.readString(script);
        String pendingMarker = "jane\\pending\\" + plan.syncId() + "\\success.marker";
        String check = "> \"" + pendingMarker + "\" echo SUCCESS";
        int index = original.indexOf(check);
        assertTrue(index > 0);
        Files.writeString(script, original.substring(0, index)
                + "md \"" + pendingMarker + "\"\r\n"
                + original.substring(index), StandardCharsets.UTF_8);

        assertNotEquals(0, run(script), output);
        assertEquals("server tool", Files.readString(extra));
        assertFalse(Files.isRegularFile(disabled(plan).resolve("success.marker")));
        assertFalse(Files.isRegularFile(pending(plan).resolve("success.marker")));
        assertTrue(Files.isRegularFile(pending(plan).resolve("failed.marker")));
    }

    private UpdatePlan plan(List<UpdatePlan.Operation> installs, List<ClientDisablePlan.Entry> cleanup)
            throws Exception {
        List<ClientDisablePlan.DependencyGuard> guards = new java.util.ArrayList<>();
        try (var files = Files.list(game.resolve("mods"))) {
            for (Path jar : files.filter(path -> path.getFileName().toString().endsWith(".jar")).toList())
                guards.add(new ClientDisablePlan.DependencyGuard(jar.getFileName().toString(), Hashing.sha512(jar)));
        }
        return new UpdatePlan(UUID.randomUUID().toString(), SERVER, STAMP, installs, cleanup, guards,
                installs.isEmpty() || cleanup.isEmpty() ? null : "a".repeat(128));
    }

    private ClientDisablePlan.Entry cleanup(Path jar, String id) throws Exception {
        return new ClientDisablePlan.Entry(id, id, "1", jar.getFileName().toString(),
                Hashing.sha512(jar), ClientCompatibilityReport.Kind.EXPLICIT_SERVER, List.of(id));
    }

    private UpdatePlan.Operation add(Path staged, String id) throws Exception {
        return new UpdatePlan.Operation(id, UpdatePlan.Kind.ADD, null, null,
                staged.getFileName().toString(), Hashing.sha512(staged), Files.size(staged));
    }

    private Path stage(String name, String value) throws Exception {
        Path staging = Files.createDirectories(game.resolve("jane/staging"));
        // The fixed sync ID is installed by prepare() before the script is started.
        return Files.writeString(staging.resolve(name), value);
    }

    private Path prepare(UpdatePlan plan) throws Exception {
        if (!plan.operations().isEmpty()) {
            Path staging = Files.createDirectories(game.resolve("jane/staging/" + plan.syncId()));
            for (UpdatePlan.Operation op : plan.operations()) {
                Path provisional = game.resolve("jane/staging").resolve(op.newFile());
                if (Files.exists(provisional)) Files.move(provisional, staging.resolve(op.newFile()));
            }
        }
        Path pending = PendingStore.create(game, plan);
        Path script = pending.resolve("update.bat");
        Files.writeString(script, WindowsBatch.generate(plan, 2147483647L), StandardCharsets.UTF_8);
        return script;
    }

    private Path pending(UpdatePlan plan) { return game.resolve("jane/pending").resolve(plan.syncId()); }
    private Path disabled(UpdatePlan plan) {
        return game.resolve("jane/disabled").resolve(SERVER).resolve(STAMP);
    }

    private int run(Path script) throws Exception {
        Process process = new ProcessBuilder("cmd.exe", "/c", game.relativize(script).toString())
                .directory(game.toFile()).redirectErrorStream(true).start();
        process.getOutputStream().write('\n');
        process.getOutputStream().close();
        boolean done = process.waitFor(40, TimeUnit.SECONDS);
        if (!done) process.destroyForcibly();
        assertTrue(done, "Updater exceeded " + Duration.ofSeconds(40));
        output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return process.exitValue();
    }
}
