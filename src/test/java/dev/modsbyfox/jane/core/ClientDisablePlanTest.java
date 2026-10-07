package dev.modsbyfox.jane.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientDisablePlanTest {
    @TempDir Path game;
    private static final String SERVER = "d".repeat(64);
    private String lastOutput;

    private ClientCompatibilityReport.ExtraMod mod(String name, String id) throws Exception {
        Path mods = Files.createDirectories(game.resolve("mods"));
        Path file = Files.writeString(mods.resolve(name), "file-" + id);
        return new ClientCompatibilityReport.ExtraMod(id, id, "1", name, Hashing.sha512(file),
                ClientCompatibilityReport.Kind.EXPLICIT_CLIENT, file);
    }

    private ClientCompatibilityReport report(ClientCompatibilityReport.ExtraMod... mods) {
        List<ClientCompatibilityReport.ExtraMod> explicit = List.of(mods);
        return new ClientCompatibilityReport(explicit, List.of(), ClientCompatibilityReport.fingerprint(explicit));
    }

    @Test void planContainsOnlySelectedExplicitExtras() throws Exception {
        var sodium = mod("sodium.jar", "sodium");
        var iris = mod("iris.jar", "iris");
        var untouched = mod("untouched.jar", "untouched");
        var required = mod("required.jar", "required");
        var inventory = report(sodium, iris, untouched);
        ClientDisablePlan plan = ClientDisablePlan.prepare(game, SERVER, inventory, List.of(sodium, iris));
        assertEquals(List.of("sodium.jar", "iris.jar"), plan.entries().stream()
                .map(ClientDisablePlan.Entry::filename).toList());
        assertThrows(java.io.IOException.class, () -> ClientDisablePlan.prepare(game, SERVER, inventory, List.of(required)));
        assertTrue(Files.exists(game.resolve("mods/untouched.jar")));
        assertTrue(Files.exists(game.resolve("mods/required.jar")));
    }

    @Test void changedSelectedHashAndDangerousNamesFailBeforeScript() throws Exception {
        var sodium = mod("sodium.jar", "sodium");
        var inventory = report(sodium);
        Files.writeString(sodium.jar(), "changed");
        assertThrows(java.io.IOException.class, () -> ClientDisablePlan.prepare(game, SERVER, inventory, List.of(sodium)));
        for (String name : List.of("evil%.jar", "evil!.jar", "evil&.jar")) {
            var unsafe = mod(name, "unsafe");
            assertThrows(IllegalArgumentException.class,
                    () -> ClientDisablePlan.prepare(game, SERVER, report(unsafe), List.of(unsafe)));
        }
        assertFalse(Files.exists(game.resolve("jane/disabled/" + SERVER)));
    }

    @Test void unicodeFilenameSurvivesPlanAndSuccessfulMove() throws Exception {
        Assumptions.assumeTrue(isWindows());
        String name = "【优化】sodium-extra.jar";
        var mod = mod(name, "sodiumextra");
        ClientDisablePlan plan = ClientDisablePlan.prepare(game, SERVER, report(mod), List.of(mod));
        assertEquals(name, plan.entries().get(0).filename());
        assertEquals(0, run(plan));
        Path disabled = game.resolve("jane/disabled/" + SERVER + "/" + plan.timestamp());
        assertFalse(Files.exists(mod.jar()));
        assertEquals(mod.sha512(), Hashing.sha512(disabled.resolve(name)));
        assertTrue(Files.isRegularFile(disabled.resolve("success.marker")));
        assertTrue(lastOutput.contains("Jane - 客户端 Mod 禁用助手"), lastOutput);
        assertTrue(lastOutput.contains("正在等待 Minecraft 关闭"), lastOutput);
        assertTrue(lastOutput.contains("Minecraft 已关闭"), lastOutput);
        assertTrue(lastOutput.contains("禁用完成"), lastOutput);
        assertTrue(lastOutput.contains("禁用备份点：" + plan.timestamp()), lastOutput);
        assertTrue(lastOutput.contains("请手动重新启动客户端"), lastOutput);
    }

    @Test void successMovesOnlySelectedAndKeepsMetadata() throws Exception {
        Assumptions.assumeTrue(isWindows());
        var sodium = mod("sodium.jar", "sodium");
        var iris = mod("iris.jar", "iris");
        var untouched = mod("untouched.jar", "untouched");
        ClientDisablePlan plan = ClientDisablePlan.prepare(game, SERVER, report(sodium, iris, untouched),
                List.of(sodium, iris));
        assertEquals(0, run(plan));
        Path disabled = game.resolve("jane/disabled/" + SERVER + "/" + plan.timestamp());
        assertFalse(Files.exists(sodium.jar()));
        assertFalse(Files.exists(iris.jar()));
        assertTrue(Files.exists(untouched.jar()));
        assertEquals(sodium.sha512(), Hashing.sha512(disabled.resolve("sodium.jar")));
        assertEquals(iris.sha512(), Hashing.sha512(disabled.resolve("iris.jar")));
        String json = Files.readString(disabled.resolve("disabled.json"));
        assertTrue(json.contains("sodium.jar"));
        assertFalse(json.contains(game.toString()));
        assertTrue(Files.isRegularFile(disabled.resolve("success.marker")));
        String log = Files.readString(disabled.resolve("disable.log"));
        assertTrue(log.contains("DISABLE " + plan.timestamp() + " SERVER " + SERVER));
        assertTrue(log.contains("MOVE sodium OK"));
        assertTrue(log.contains("MOVE iris OK"));
        assertTrue(log.contains("SUCCESS"));
        assertFalse(log.contains(game.toString()));
        assertTrue(log.chars().allMatch(character -> character < 128), "disable.log must be ASCII");
    }

    @Test void secondMoveFailureRollsBackFirst() throws Exception {
        Assumptions.assumeTrue(isWindows());
        var sodium = mod("sodium.jar", "sodium");
        var iris = mod("iris.jar", "iris");
        ClientDisablePlan plan = ClientDisablePlan.prepare(game, SERVER, report(sodium, iris), List.of(sodium, iris));
        Path script = ClientDisableBatch.write(game, plan, 2147483647L);
        Path disabled = script.getParent();
        Files.writeString(disabled.resolve("iris.jar"), "collision");
        assertNotEquals(0, runScript(script));
        assertEquals(sodium.sha512(), Hashing.sha512(sodium.jar()));
        assertEquals(iris.sha512(), Hashing.sha512(iris.jar()));
        assertTrue(Files.isRegularFile(disabled.resolve("failed.marker")));
        assertFalse(Files.exists(disabled.resolve("success.marker")));
        String log = Files.readString(disabled.resolve("disable.log"));
        assertTrue(log.contains("MOVE sodium OK"));
        assertTrue(log.contains("ROLLBACK sodium OK"));
        assertTrue(log.contains("FAILED"));
        assertTrue(lastOutput.contains("禁用未完成"), lastOutput);
        assertTrue(lastOutput.contains("已移动的文件已尝试恢复"), lastOutput);
    }

    @Test void rollbackFailureIsReportedWithoutClaimingFullRestoration() throws Exception {
        Assumptions.assumeTrue(isWindows());
        var sodium = mod("sodium.jar", "sodium");
        var iris = mod("iris.jar", "iris");
        ClientDisablePlan plan = ClientDisablePlan.prepare(game, SERVER, report(sodium, iris), List.of(sodium, iris));
        Path script = ClientDisableBatch.write(game, plan, 2147483647L);
        Path disabled = script.getParent();
        Files.writeString(disabled.resolve("iris.jar"), "collision");
        String source = Files.readString(script);
        String check = "if exist \"jane\\disabled\\" + SERVER + "\\" + plan.timestamp()
                + "\\iris.jar\" goto rollback";
        assertTrue(source.contains(check));
        Files.writeString(script, source.replace(check,
                "> \"mods\\sodium.jar\" echo collision\r\n" + check), StandardCharsets.UTF_8);
        assertNotEquals(0, runScript(script));
        assertTrue(Files.isRegularFile(disabled.resolve("failed.marker")));
        assertEquals(sodium.sha512(), Hashing.sha512(disabled.resolve("sodium.jar")));
        assertTrue(lastOutput.contains("恢复过程遇到错误"), lastOutput);
        String log = Files.readString(disabled.resolve("disable.log"));
        assertTrue(log.contains("ROLLBACK sodium FAILED"));
        assertTrue(log.contains("FAILED"));
    }

    @Test void longBatchKeepsChineseCompletionTextAndShortServerId() throws Exception {
        Assumptions.assumeTrue(isWindows());
        Path mods = Files.createDirectories(game.resolve("mods"));
        List<ClientDisablePlan.Entry> entries = new java.util.ArrayList<>();
        for (int i = 0; i < 84; i++) {
            String name = "mod" + i + ".jar";
            Path jar = Files.writeString(mods.resolve(name), "file-" + i);
            entries.add(new ClientDisablePlan.Entry("mod" + i, "Mod " + i, "1", name, Hashing.sha512(jar)));
        }
        ClientDisablePlan plan = new ClientDisablePlan(SERVER, "2026-10-07_02-30-15", entries);
        assertEquals(0, run(plan));
        assertTrue(lastOutput.contains("禁用备份点：" + plan.timestamp()), lastOutput);
        assertTrue(lastOutput.contains("jane\\disabled\\" + SERVER.substring(0, 12) + "...\\" + plan.timestamp()), lastOutput);
        assertFalse(lastOutput.contains(SERVER), "Console must not reveal full server ID");
        assertTrue(lastOutput.contains("请手动重新启动客户端"), lastOutput);
    }

    private int run(ClientDisablePlan plan) throws Exception {
        Path script = ClientDisableBatch.write(game, plan, 2147483647L);
        return runScript(script);
    }

    private int runScript(Path script) throws Exception {
        Process process = new ProcessBuilder("cmd.exe", "/c", game.relativize(script).toString())
                .directory(game.toFile()).redirectErrorStream(true).start();
        process.getOutputStream().write('\n');
        process.getOutputStream().close();
        boolean done = process.waitFor(30, TimeUnit.SECONDS);
        if (!done) process.destroyForcibly();
        assertTrue(done, "Disable BAT exceeded " + Duration.ofSeconds(30));
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertFalse(output.contains("not recognized"), output);
        lastOutput = output;
        return process.exitValue();
    }

    private static boolean isWindows() { return System.getProperty("os.name", "").toLowerCase().contains("windows"); }
}
