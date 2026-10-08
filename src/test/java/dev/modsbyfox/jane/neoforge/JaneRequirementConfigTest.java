package dev.modsbyfox.jane.neoforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JaneRequirementConfigTest {
    @TempDir Path gameDir;

    @Test
    void absentConfigHasNoOverrideAndDoesNotCreateDirectories() throws IOException {
        var config = JaneRequirementConfig.read(gameDir);
        assertEquals(ClientRequirementClassifier.Override.NONE,
                config.forJar(jar("bundle.jar", "alpha")));
        assertEquals(false, Files.exists(gameDir.resolve("config")));
    }

    @Test
    void requiredWinsConflictsAndPartialPerIdExclusionDoesNotDropWholeJar() throws IOException {
        write("{" +
                "\"byModId\":{\"alpha\":\"NOT_REQUIRED\",\"beta\":\"REQUIRED\"}," +
                "\"byJar\":{\"bundle.jar\":\"NOT_REQUIRED\",\"other.jar\":\"REQUIRED\",\"whole.jar\":\"NOT_REQUIRED\"}" +
                "}");
        var config = JaneRequirementConfig.read(gameDir);
        assertEquals(ClientRequirementClassifier.Override.REQUIRED,
                config.forJar(jar("bundle.jar", "alpha", "beta")));
        assertEquals(ClientRequirementClassifier.Override.REQUIRED,
                config.forJar(jar("other.jar", "alpha")));
        assertEquals(ClientRequirementClassifier.Override.NOT_REQUIRED,
                config.forJar(jar("whole.jar", "gamma", "delta")));
        assertEquals(ClientRequirementClassifier.Override.NONE,
                config.forJar(jar("unlisted.jar", "alpha", "gamma")));
        assertEquals(ClientRequirementClassifier.Override.NOT_REQUIRED,
                config.forJar(jar("unlisted.jar", "alpha")));
    }

    @Test
    void allIdsCanExplicitlyExcludeAMultiModJar() throws IOException {
        write("{\"byModId\":{\"alpha\":\"NOT_REQUIRED\",\"beta\":\"NOT_REQUIRED\"}}");
        assertEquals(ClientRequirementClassifier.Override.NOT_REQUIRED,
                JaneRequirementConfig.read(gameDir).forJar(jar("bundle.jar", "alpha", "beta")));
    }

    @Test
    void identityChangesWhenSameSizeConfigContentChangesWithoutMtimeChange() throws IOException {
        String firstContent = "{\"byModId\":{\"alpha\":\"REQUIRED\"}}";
        String secondContent = "{\"byModId\":{\"bravo\":\"REQUIRED\"}}";
        assertEquals(firstContent.getBytes(StandardCharsets.UTF_8).length,
                secondContent.getBytes(StandardCharsets.UTF_8).length);

        write(firstContent);
        Path file = configPath();
        FileTime originalTime = Files.getLastModifiedTime(file);
        String firstIdentity = JaneRequirementConfig.read(gameDir).identity();
        assertEquals(firstIdentity, JaneRequirementConfig.read(gameDir).identity());

        write(secondContent);
        Files.setLastModifiedTime(file, originalTime);
        assertEquals(originalTime, Files.getLastModifiedTime(file));
        JaneRequirementConfig changed = JaneRequirementConfig.read(gameDir);
        assertNotEquals(firstIdentity, changed.identity());
        assertEquals(ClientRequirementClassifier.Override.NONE,
                changed.forJar(jar("alpha.jar", "alpha")));
        assertEquals(ClientRequirementClassifier.Override.REQUIRED,
                changed.forJar(jar("bravo.jar", "bravo")));
    }

    @Test
    void rejectsUnknownDuplicateAndMalformedOverrides() throws IOException {
        for (String json : List.of(
                "{\"other\":{}}",
                "{\"byJar\":{},\"byJar\":{}}",
                "{\"byModId\":{\"alpha\":\"REQUIRED\",\"alpha\":\"NOT_REQUIRED\"}}",
                "{\"byModId\":{\"jane\":\"REQUIRED\"}}",
                "{\"byJar\":{\"../outside.jar\":\"REQUIRED\"}}",
                "{\"byModId\":{\"alpha\":\"NONE\"}}",
                "{\"byModId\":{\"alpha\":true}}",
                "{\"byJar\":null}")) {
            write(json);
            assertThrows(IOException.class, () -> JaneRequirementConfig.read(gameDir), json);
        }
    }

    @Test
    void rejectsOversizeAndUnsafeConfigPath() throws IOException {
        write(" ".repeat(64 * 1024 + 1));
        assertThrows(IOException.class, () -> JaneRequirementConfig.read(gameDir));
        Path requirements = gameDir.resolve("config/jane/requirements.json");
        Files.delete(requirements);
        Files.createDirectory(requirements);
        assertThrows(IOException.class, () -> JaneRequirementConfig.read(gameDir));
    }

    @Test
    void rejectsMalformedUtf8() throws IOException {
        Path file = configPath();
        Files.write(file, new byte[] {(byte) 0xC3, (byte) 0x28});
        assertThrows(IOException.class, () -> JaneRequirementConfig.read(gameDir));
    }

    private NeoForgePhysicalDiscovery.PhysicalJar jar(String fileName, String... ids) {
        return new NeoForgePhysicalDiscovery.PhysicalJar(gameDir.resolve("mods").resolve(fileName),
                List.of(ids), ids[0], ids[0], "1",
                new ClientRequirementClassifier.FmlEvidence(false, List.of()));
    }

    private void write(String json) throws IOException {
        Files.writeString(configPath(), json, StandardCharsets.UTF_8);
    }

    private Path configPath() throws IOException {
        Path jane = gameDir.resolve("config/jane");
        Files.createDirectories(jane);
        return jane.resolve("requirements.json");
    }
}
