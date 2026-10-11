package dev.modsbyfox.jane.neoforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiscoveredModsStoreTest {
    @TempDir Path gameDir;

    @Test
    void writesOnlyLocalDiagnosticDataAndReplacesPreviousSnapshot() throws IOException {
        var required = classified("required", ClientRequirement.REQUIRED);
        var serverOnly = classified("serveronly", ClientRequirement.NOT_REQUIRED);
        Path target = gameDir.resolve("config/jane/discovered-mods.json");

        DiscoveredModsStore.write(gameDir, "1.1.8.3-beta", List.of(required));
        DiscoveredModsStore.write(gameDir, "1.1.8.3-beta", List.of(required, serverOnly));

        String text = Files.readString(target);
        var json = JsonParser.parseString(text).getAsJsonObject();
        assertEquals(2, json.get("schemaVersion").getAsInt());
        assertEquals("1.1.8.3-beta", json.get("janeVersion").getAsString());
        var mods = json.getAsJsonArray("mods");
        assertEquals(2, mods.size());
        assertEquals("SYNC", mods.get(0).getAsJsonObject().get("syncDecision").getAsString());
        assertEquals("EXCLUDE", mods.get(1).getAsJsonObject().get("syncDecision").getAsString());
        assertEquals("required.jar", mods.get(0).getAsJsonObject().get("fileName").getAsString());
        assertEquals("a".repeat(128), mods.get(0).getAsJsonObject().get("sha512").getAsString());
        assertFalse(text.contains(gameDir.toString()));
        try (var files = Files.list(target.getParent())) {
            assertEquals(List.of(target), files.toList());
        }
    }

    @Test
    void refusesToReplaceADirectoryWithDiagnosticOutput() throws IOException {
        Path target = Files.createDirectories(gameDir.resolve("config/jane/discovered-mods.json"));
        assertThrows(IOException.class, () -> DiscoveredModsStore.write(gameDir,
                "1.1.8.3-beta", List.of(classified("required", ClientRequirement.REQUIRED))));
        assertTrue(Files.isDirectory(target));
    }

    @Test
    void refusesAGameDirectoryAlias() throws IOException {
        Path real = Files.createDirectory(gameDir.resolve("real"));
        Path alias = gameDir.resolve("alias");
        try {
            Files.createSymbolicLink(alias, real);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable in this test environment");
            return;
        }
        assertThrows(IOException.class, () -> DiscoveredModsStore.write(alias,
                "1.1.8.3-beta", List.of()));
        assertFalse(Files.exists(real.resolve("config")));
    }

    private ServerManifest.Classified classified(String id, ClientRequirement requirement) {
        var physical = new NeoForgePhysicalDiscovery.PhysicalJar(
                gameDir.resolve("mods").resolve(id + ".jar"), List.of(id), id, id, "1.0",
                new ClientRequirementClassifier.FmlEvidence(true,
                        List.of(ClientRequirementClassifier.EntrypointSide.DEFAULT)));
        var decision = new ClientRequirementClassifier.Result(requirement,
                "Local NeoForge evidence", List.of(), true, List.of());
        return new ServerManifest.Classified(physical, decision, 12, "a".repeat(128));
    }
}
