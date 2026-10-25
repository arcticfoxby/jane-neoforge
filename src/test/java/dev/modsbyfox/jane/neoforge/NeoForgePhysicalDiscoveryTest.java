package dev.modsbyfox.jane.neoforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.modsbyfox.jane.core.ClientCleanupGraph;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.modscan.ModAnnotation;
import net.neoforged.neoforgespi.language.IModInfo;
import org.apache.maven.artifact.versioning.VersionRange;
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
        Path nested = Files.createFile(Files.createDirectory(gameDir.resolve("nested-cache"))
                .resolve("nested.jar"));
        Path other = Files.createFile(gameDir.resolve("other.jar"));
        var found = NeoForgePhysicalDiscovery.fromLoadedFiles(gameDir, List.of(
                loaded(jane, false, List.of(mod("jane", "Jane", "1")), List.of(), false),
                loadedNested(nested, other, List.of(mod("nested", "Nested", "1")), List.of(), false),
                loaded(other, false, List.of(mod("other", "Other", "1")), List.of(), false)));
        assertTrue(found.isEmpty());
    }

    @Test
    void nestedModIdsBelongToTheOuterPhysicalJarAndPreventServerOnlyExclusion() throws IOException {
        Path mods = Files.createDirectory(gameDir.resolve("mods"));
        Path outer = Files.createFile(mods.resolve("bundle.jar"));
        Path inner = Files.createFile(Files.createDirectory(gameDir.resolve("nested-cache"))
                .resolve("inner.jar"));
        var found = NeoForgePhysicalDiscovery.fromLoadedFiles(gameDir, List.of(
                loaded(outer, false, List.of(mod("outer", "Outer", "1")),
                        List.of(entry("outer", ClientRequirementClassifier.EntrypointSide.DEDICATED_SERVER)), true),
                loadedNested(inner, outer, List.of(mod("inner", "Inner", "2")),
                        List.of(entry("inner", ClientRequirementClassifier.EntrypointSide.DEDICATED_SERVER)), true)));

        assertEquals(1, found.size());
        var physical = found.getFirst();
        assertEquals(outer.toRealPath(), physical.jar());
        assertEquals(List.of("inner", "outer"), physical.modIds());
        assertFalse(physical.fmlEvidence().complete());
        var decision = ClientRequirementClassifier.classify(new ClientRequirementClassifier.Input(
                ClientRequirementClassifier.Override.NONE, physical.fmlEvidence(), null));
        assertEquals(ClientRequirement.UNKNOWN, decision.requirement());
        assertTrue(decision.includeInClientManifest());
    }

    @Test
    void libraryShellWithOnlyNestedModStillBecomesOnePhysicalJar() throws IOException {
        Path mods = Files.createDirectory(gameDir.resolve("mods"));
        Path shell = Files.createFile(mods.resolve("shell.jar"));
        Path inner = Files.createFile(Files.createDirectory(gameDir.resolve("nested-cache"))
                .resolve("inner.jar"));
        var found = NeoForgePhysicalDiscovery.fromLoadedFiles(gameDir, List.of(
                loadedNested(inner, shell, List.of(mod("inner", "Inner", "2")),
                        List.of(entry("inner", ClientRequirementClassifier.EntrypointSide.DEDICATED_SERVER)), true)));

        assertEquals(1, found.size());
        var physical = found.getFirst();
        assertEquals(shell.toRealPath(), physical.jar());
        assertEquals(List.of("inner"), physical.modIds());
        assertEquals("inner", physical.canonicalId());
        assertFalse(physical.fmlEvidence().complete());
        var decision = ClientRequirementClassifier.classify(new ClientRequirementClassifier.Input(
                ClientRequirementClassifier.Override.NONE, physical.fmlEvidence(), null));
        assertTrue(decision.includeInClientManifest());
    }

    @Test
    void nestedModWithoutPhysicalParentFailsClosed() throws IOException {
        Files.createDirectory(gameDir.resolve("mods"));
        Path inner = Files.createFile(gameDir.resolve("inner.jar"));
        assertThrows(IOException.class, () -> NeoForgePhysicalDiscovery.fromLoadedFiles(gameDir, List.of(
                loaded(inner, true, List.of(mod("inner", "Inner", "1")), List.of(), true))));
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
    void dependencyTargetSideIsRecordedWithoutExcludingOwnerJar() throws IOException {
        Path mods = Files.createDirectory(gameDir.resolve("mods"));
        Path jar = Files.createFile(mods.resolve("owner.jar"));
        String dependency = "owner=owner target=server_helper type=REQUIRED targetSide=SERVER";
        var found = NeoForgePhysicalDiscovery.fromLoadedFiles(gameDir, List.of(
                loaded(jar, false, List.of(new NeoForgePhysicalDiscovery.LoadedMod(
                                "owner", "Owner", "1", List.of(dependency))),
                        List.of(entry("owner", ClientRequirementClassifier.EntrypointSide.DEFAULT)), true)));
        assertEquals(List.of(dependency), found.getFirst().dependencyDescriptions());
        var classification = ClientRequirementClassifier.classify(new ClientRequirementClassifier.Input(
                ClientRequirementClassifier.Override.NONE, found.getFirst().fmlEvidence(), null));
        assertEquals(ClientRequirement.UNKNOWN, classification.requirement());
    }

    @Test
    void resolvedFmlDependenciesRetainOwnerTypeSideAndVersionRange() throws Exception {
        VersionRange range = VersionRange.createFromVersionSpec("[1,2)");
        IModInfo.ModVersion required = dependency("target", IModInfo.DependencyType.REQUIRED,
                IModInfo.DependencySide.CLIENT, range);
        IModInfo.ModVersion optional = dependency("other", IModInfo.DependencyType.OPTIONAL,
                IModInfo.DependencySide.SERVER, range);
        IModInfo owner = (IModInfo) Proxy.newProxyInstance(IModInfo.class.getClassLoader(),
                new Class<?>[] { IModInfo.class }, (proxy, method, args) -> switch (method.getName()) {
                    case "getModId" -> "owner";
                    case "getDependencies" -> List.of(required, optional);
                    default -> null;
                });
        var facts = NeoForgePhysicalDiscovery.readDependencies(owner);

        assertTrue(facts.complete());
        assertEquals(2, facts.dependencies().size());
        assertEquals(new ClientCleanupGraph.Dependency("owner", "target",
                        ClientCleanupGraph.DependencyType.REQUIRED,
                        ClientCleanupGraph.DependencySide.CLIENT, "[1,2)"),
                facts.dependencies().getFirst());
        assertEquals(ClientCleanupGraph.DependencyType.OPTIONAL,
                facts.dependencies().get(1).type());
        assertEquals(ClientCleanupGraph.DependencySide.SERVER,
                facts.dependencies().get(1).side());
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

    private static IModInfo.ModVersion dependency(String target, IModInfo.DependencyType type,
            IModInfo.DependencySide side, VersionRange range) {
        return (IModInfo.ModVersion) Proxy.newProxyInstance(IModInfo.ModVersion.class.getClassLoader(),
                new Class<?>[] { IModInfo.ModVersion.class }, (proxy, method, args) -> switch (method.getName()) {
                    case "getModId" -> target;
                    case "getType" -> type;
                    case "getSide" -> side;
                    case "getVersionRange" -> range;
                    default -> null;
                });
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

    private static NeoForgePhysicalDiscovery.LoadedFile loadedNested(Path path, Path outer,
            List<NeoForgePhysicalDiscovery.LoadedMod> mods,
            List<NeoForgePhysicalDiscovery.Entrypoint> entrypoints, boolean complete) {
        return new NeoForgePhysicalDiscovery.LoadedFile(path, true, outer, mods, entrypoints, complete);
    }
}
