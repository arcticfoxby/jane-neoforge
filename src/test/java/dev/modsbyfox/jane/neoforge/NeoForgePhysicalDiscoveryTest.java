package dev.modsbyfox.jane.neoforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.modscan.ModAnnotation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.Type;

class NeoForgePhysicalDiscoveryTest {
    @TempDir Path gameDir;

    @Test
    void groupsAllIdsFromOnePhysicalJarAndChoosesStableCanonicalMetadata() throws IOException {
        Path mods = Files.createDirectory(gameDir.resolve("mods"));
        Path jar = Files.createFile(mods.resolve("bundle.jar"));
        var beta = loaded(jar, false, List.of(mod("beta", "Beta", "2")),
                List.of(entry("beta", ClientRequirementClassifier.EntrypointSide.DEDICATED_SERVER)), true);
        var alpha = loaded(jar, false, List.of(mod("alpha", "Alpha", "1")),
                List.of(entry("alpha", ClientRequirementClassifier.EntrypointSide.DEDICATED_SERVER)), true);

        var found = NeoForgePhysicalDiscovery.fromLoadedFiles(gameDir, List.of(beta, alpha));

        assertEquals(1, found.size());
        var physical = found.getFirst();
        assertEquals(jar.toRealPath(), physical.jar());
        assertEquals(List.of("alpha", "beta"), physical.modIds());
        assertEquals("alpha", physical.canonicalId());
        assertEquals("Alpha", physical.displayName());
        assertEquals("1", physical.version());
        assertTrue(physical.fmlEvidence().complete());
        assertEquals(2, physical.fmlEvidence().entrypoints().size());
    }

    @Test
    void excludesJaneNestedFilesAndAnythingOutsideThisInstancesMods() throws IOException {
        Path mods = Files.createDirectory(gameDir.resolve("mods"));
        Path jane = Files.createFile(mods.resolve("jane.jar"));
        Path nested = Files.createFile(mods.resolve("nested.jar"));
        Path other = Files.createFile(gameDir.resolve("other.jar"));
        var found = NeoForgePhysicalDiscovery.fromLoadedFiles(gameDir, List.of(
                loaded(jane, false, List.of(mod("jane", "Jane", "1")), List.of(), false),
                loaded(nested, true, List.of(mod("nested", "Nested", "1")), List.of(), false),
                loaded(other, false, List.of(mod("other", "Other", "1")), List.of(), false)));
        assertTrue(found.isEmpty());
    }

    @Test
    void mixedJaneBundleFailsClosed() throws IOException {
        Path mods = Files.createDirectory(gameDir.resolve("mods"));
        Path mixed = Files.createFile(mods.resolve("mixed.jar"));
        assertThrows(IOException.class, () -> NeoForgePhysicalDiscovery.fromLoadedFiles(gameDir, List.of(
                loaded(mixed, false, List.of(mod("jane", "Jane", "1"), mod("other", "Other", "1")),
                        List.of(), false))));
    }

    @Test
    void missingTomlModEntrypointMakesEvidenceIncomplete() throws IOException {
        Path mods = Files.createDirectory(gameDir.resolve("mods"));
        Path jar = Files.createFile(mods.resolve("mixed.jar"));
        var found = NeoForgePhysicalDiscovery.fromLoadedFiles(gameDir, List.of(
                loaded(jar, false, List.of(mod("code", "Code", "1"), mod("data", "Data", "1")),
                        List.of(entry("code", ClientRequirementClassifier.EntrypointSide.DEDICATED_SERVER)), true)));
        assertFalse(found.getFirst().fmlEvidence().complete());
        var classification = ClientRequirementClassifier.classify(new ClientRequirementClassifier.Input(
                ClientRequirementClassifier.Override.NONE, found.getFirst().fmlEvidence(), null));
        assertTrue(classification.includeInClientManifest());
    }

    @Test
    void rejectsUnsafeDirectJarName() throws IOException {
        Path mods = Files.createDirectory(gameDir.resolve("mods"));
        Path jar = Files.createFile(mods.resolve("bad%.jar"));
        assertThrows(IOException.class, () -> NeoForgePhysicalDiscovery.fromLoadedFiles(gameDir, List.of(
                loaded(jar, false, List.of(mod("example", "Example", "1")), List.of(), false))));
    }

    @Test
    void decodesOnlyRecognizedModDistScanValues() {
        String descriptor = Type.getDescriptor(Dist.class);
        var server = new ModAnnotation.EnumHolder(descriptor, "DEDICATED_SERVER");
        var client = new ModAnnotation.EnumHolder(descriptor, "CLIENT");
        assertEquals(ClientRequirementClassifier.EntrypointSide.DEFAULT,
                NeoForgePhysicalDiscovery.entrypointSide(Map.of()));
        assertEquals(ClientRequirementClassifier.EntrypointSide.DEDICATED_SERVER,
                NeoForgePhysicalDiscovery.entrypointSide(Map.of("dist", List.of(server))));
        assertEquals(ClientRequirementClassifier.EntrypointSide.BOTH,
                NeoForgePhysicalDiscovery.entrypointSide(Map.of("dist", List.of(server, client))));
        assertNull(NeoForgePhysicalDiscovery.entrypointSide(Map.of("dist", List.of())));
        assertNull(NeoForgePhysicalDiscovery.entrypointSide(Map.of("dist", List.of("SERVER"))));
    }

    private static NeoForgePhysicalDiscovery.LoadedMod mod(String id, String name, String version) {
        return new NeoForgePhysicalDiscovery.LoadedMod(id, name, version);
    }

    private static NeoForgePhysicalDiscovery.Entrypoint entry(
            String id, ClientRequirementClassifier.EntrypointSide side) {
        return new NeoForgePhysicalDiscovery.Entrypoint(id, side);
    }

    private static NeoForgePhysicalDiscovery.LoadedFile loaded(Path path, boolean nested,
            List<NeoForgePhysicalDiscovery.LoadedMod> mods,
            List<NeoForgePhysicalDiscovery.Entrypoint> entrypoints, boolean complete) {
        return new NeoForgePhysicalDiscovery.LoadedFile(path, nested, mods, entrypoints, complete);
    }
}
