package dev.modsbyfox.jane.core;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

@EnabledOnOs(OS.WINDOWS)
class PathSafetyReparseTest {
    @TempDir Path root;

    @Test void rejectsWindowsJunctionAsGameRoot() throws Exception {
        Path actual = Files.createDirectory(root.resolve("actual"));
        Path mods = Files.createDirectory(actual.resolve("mods"));
        Files.writeString(mods.resolve("test.jar"), "payload");
        Path junction = root.resolve("junction");
        Process process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J",
                junction.toString(), actual.toString()).redirectErrorStream(true).start();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        String output = new String(process.getInputStream().readAllBytes());
        assertTrue(process.exitValue() == 0, output);
        assertThrows(java.io.IOException.class,
                () -> PathSafety.existingJarNamedInMods(junction, "test.jar"));
        assertThrows(java.io.IOException.class,
                () -> PathSafety.janeDirectoryPath(junction, "disabled"));
    }

    @Test void rejectsWindowsJunctionAsModsDirectory() throws Exception {
        Path game = Files.createDirectory(root.resolve("game"));
        Path external = Files.createDirectory(root.resolve("external-mods"));
        Files.writeString(external.resolve("test.jar"), "payload");
        Path junction = game.resolve("mods");
        Process process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J",
                junction.toString(), external.toString()).redirectErrorStream(true).start();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        String output = new String(process.getInputStream().readAllBytes());
        assertTrue(process.exitValue() == 0, output);
        assertThrows(java.io.IOException.class,
                () -> PathSafety.existingJarNamedInMods(game, "test.jar"));
    }
}
