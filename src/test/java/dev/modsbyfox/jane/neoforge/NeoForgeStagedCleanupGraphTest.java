package dev.modsbyfox.jane.neoforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.modsbyfox.jane.core.ClientCleanupGraph;
import dev.modsbyfox.jane.core.Hashing;
import dev.modsbyfox.jane.core.PathSafety;
import dev.modsbyfox.jane.core.UpdatePlan;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NeoForgeStagedCleanupGraphTest {
    @TempDir Path gameDir;

    @Test
    void stagedRequiredOwnerBlocksCleanupOfExistingTarget() throws IOException {
        Path stage = stagedJar("future.jar", toml("javafml", "CLIENT"), Map.of());
        ClientCleanupGraph live = new ClientCleanupGraph(List.of(jar("target.jar", "target")),
                List.of(), true);
        ClientCleanupGraph future = NeoForgeStagedCleanupGraph.merge(gameDir, live,
                plan(stage, "future", UpdatePlan.Kind.ADD, null));

        assertEquals(2, future.jars().size());
        var result = future.evaluate(java.util.Set.of("target.jar"));
        assertFalse(result.safe());
        assertEquals(ClientCleanupGraph.Reason.REQUIRED_BY_OTHER_MOD,
                result.decision("target.jar").block().reason());
        assertEquals("future", result.decision("target.jar").block().ownerModId());
    }

    @Test
    void stagedServerSideDependencyDoesNotBlockAndReplacementRemovesOldMetadata()
            throws IOException {
        Path stage = stagedJar("future.jar", toml("javafml", "SERVER"), Map.of());
        ClientCleanupGraph live = new ClientCleanupGraph(List.of(
                jar("target.jar", "target"), jar("old.jar", "future")), List.of(), true);
        ClientCleanupGraph future = NeoForgeStagedCleanupGraph.merge(gameDir, live,
                plan(stage, "future", UpdatePlan.Kind.REPLACE, "old.jar"));

        assertEquals(2, future.jars().size());
        assertTrue(future.jars().stream().noneMatch(jar -> jar.filename().equals("old.jar")));
        assertTrue(future.evaluate(java.util.Set.of("target.jar")).safe());
    }

    @Test
    void incompleteOrUnsafeStagedMetadataFailsClosed() throws IOException {
        Path lowcode = stagedJar("lowcode.jar", toml("lowcodefml", "BOTH"), Map.of());
        assertThrows(IOException.class, () -> NeoForgeStagedCleanupGraph.inspect(lowcode, "lowcode.jar"));
        Path nested = stagedJar("nested.jar", toml("javafml", "BOTH"),
                Map.of("META-INF/jarjar/child.jar", new byte[] { 1 }));
        assertThrows(IOException.class, () -> NeoForgeStagedCleanupGraph.inspect(nested, "nested.jar"));
        Path ambiguousMetadata = stagedJar("ambiguous.jar", toml("javafml", "BOTH"),
                Map.of("META-INF/NEOFORGE.MODS.TOML", new byte[] { 1 }));
        assertThrows(IOException.class,
                () -> NeoForgeStagedCleanupGraph.inspect(ambiguousMetadata, "ambiguous.jar"));
        Path unknownOwner = stagedJar("unknown.jar", toml("javafml", "BOTH")
                .replace("dependencies.future", "dependencies.ghost"), Map.of());
        assertThrows(IOException.class, () -> NeoForgeStagedCleanupGraph.inspect(unknownOwner, "unknown.jar"));
        Path selfBundled = stagedJar("self.jar", toml("javafml", "BOTH")
                + "[[mods]]\nmodId=\"jane\"\nversion=\"1.0\"\ndisplayName=\"Jane\"\n",
                Map.of());
        assertThrows(IOException.class, () -> NeoForgeStagedCleanupGraph.inspect(selfBundled, "self.jar"));
        assertEquals(java.util.Set.of("future", "jane"), java.util.Set.copyOf(
                NeoForgeStagedCleanupGraph.inspectCurrent(selfBundled, "self.jar").modIds()));
        Path duplicate = stagedJar("duplicate.jar", toml("javafml", "BOTH")
                + "[[mods]]\nmodId=\"future\"\nversion=\"2.0\"\ndisplayName=\"Duplicate\"\n",
                Map.of());
        assertThrows(IOException.class, () -> NeoForgeStagedCleanupGraph.inspect(duplicate, "duplicate.jar"));
        Path invalidFmlId = stagedJar("invalid-id.jar", toml("javafml", "BOTH")
                .replace("modId=\"future\"", "modId=\"future.extra\""), Map.of());
        assertThrows(IOException.class,
                () -> NeoForgeStagedCleanupGraph.inspect(invalidFmlId, "invalid-id.jar"));
    }

    @Test
    void stagedMultiModJarKeepsEveryIdAndOwnerDependency() throws IOException {
        String metadata = toml("javafml", "CLIENT")
                + "[[mods]]\nmodId=\"helper\"\nversion=\"1.0\"\ndisplayName=\"Helper\"\n"
                + "[[dependencies.helper]]\nmodId=\"target\"\ntype=\"optional\"\n"
                + "versionRange=\"[1,)\"\nside=\"BOTH\"\n";
        Path stage = stagedJar("multi.jar", metadata, Map.of());
        var parsed = NeoForgeStagedCleanupGraph.inspect(stage, "multi.jar");
        assertEquals(java.util.Set.of("future", "helper"), java.util.Set.copyOf(parsed.modIds()));
        assertEquals(2, parsed.dependencies().size());
        assertTrue(parsed.dependencies().stream().anyMatch(dep ->
                dep.ownerModId().equals("helper")
                        && dep.type() == ClientCleanupGraph.DependencyType.OPTIONAL));
    }

    @Test
    void blankVersionRangeUsesNeoForgeDefaultAnyVersion() throws IOException {
        Path stage = stagedJar("no-range.jar", toml("javafml", "CLIENT")
                .replace("versionRange=\"[1,)\"", "versionRange=\"\""), Map.of());
        var parsed = NeoForgeStagedCleanupGraph.inspect(stage, "no-range.jar");
        assertEquals("", parsed.dependencies().getFirst().versionRange());
    }

    @Test
    void changedStagedHashBlocksFutureGraph() throws IOException {
        Path stage = stagedJar("future.jar", toml("javafml", "CLIENT"), Map.of());
        UpdatePlan plan = plan(stage, "future", UpdatePlan.Kind.ADD, null);
        Files.writeString(stage, "changed", StandardCharsets.UTF_8);
        ClientCleanupGraph live = new ClientCleanupGraph(List.of(jar("target.jar", "target")),
                List.of(), true);
        assertThrows(IOException.class, () -> NeoForgeStagedCleanupGraph.merge(gameDir, live, plan));
    }

    @Test
    void preexistingWindowsFilenameCollisionCannotBeHiddenByReplacement() throws IOException {
        Path stage = stagedJar("future.jar", toml("javafml", "CLIENT"), Map.of());
        ClientCleanupGraph live = new ClientCleanupGraph(List.of(
                jar("old.jar", "future"), jar("OLD.JAR", "other"),
                jar("target.jar", "target")), List.of(), true);
        assertThrows(IOException.class, () -> NeoForgeStagedCleanupGraph.merge(gameDir, live,
                plan(stage, "future", UpdatePlan.Kind.REPLACE, "old.jar")));
    }

    private Path stagedJar(String name, String toml, Map<String, byte[]> extras) throws IOException {
        Path staging = PathSafety.janeDirectory(gameDir, "staging", SYNC_ID);
        Path jar = staging.resolve(name);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            add(zip, "META-INF/neoforge.mods.toml", toml.getBytes(StandardCharsets.UTF_8));
            for (var entry : extras.entrySet()) add(zip, entry.getKey(), entry.getValue());
        }
        return jar;
    }

    private static void add(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    private static String toml(String loader, String side) {
        return "modLoader=\"" + loader + "\"\n"
                + "loaderVersion=\"[1,)\"\nlicense=\"Test\"\n"
                + "[[mods]]\nmodId=\"future\"\nversion=\"1.0\"\ndisplayName=\"Future\"\n"
                + "[[dependencies.future]]\nmodId=\"target\"\ntype=\"required\"\n"
                + "versionRange=\"[1,)\"\nside=\"" + side + "\"\n";
    }

    private static ClientCleanupGraph.PhysicalJar jar(String filename, String id) {
        return new ClientCleanupGraph.PhysicalJar(filename, List.of(id), List.of(), true);
    }

    private static final String SYNC_ID = "00000000-0000-0000-0000-000000000001";

    private static UpdatePlan plan(Path staged, String id, UpdatePlan.Kind kind,
                                   String oldFile) throws IOException {
        UpdatePlan.Operation operation = new UpdatePlan.Operation(id, kind, oldFile,
                oldFile == null ? null : "0".repeat(128), staged.getFileName().toString(),
                Hashing.sha512(staged), Files.size(staged));
        return new UpdatePlan(SYNC_ID, "0".repeat(64), "2026-10-26_12-00-00", List.of(operation));
    }
}
