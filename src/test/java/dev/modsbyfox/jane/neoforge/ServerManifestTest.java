package dev.modsbyfox.jane.neoforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.modsbyfox.jane.core.Hashing;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerManifestTest {
    @TempDir Path gameDir;

    @Test
    void onePhysicalJarWithMultipleModIdsBecomesOneManifestEntry() throws IOException {
        Path jar = jar("bundle.jar", "bundle");
        var physical = physical(jar, List.of("alpha", "beta"), true,
                ClientRequirementClassifier.EntrypointSide.DEFAULT,
                ClientRequirementClassifier.EntrypointSide.DEDICATED_SERVER);

        var prepared = ServerManifest.prepare(gameDir, List.of(physical), (item, hash) -> ServerManifest.Evidence.NONE);

        assertEquals(1, prepared.manifest().entries().size());
        assertEquals("alpha", prepared.manifest().entries().getFirst().modId());
        assertEquals(Hashing.sha512(jar), prepared.manifest().entries().getFirst().sha512());
        assertEquals(jar.toRealPath(), prepared.filesByHash().get(Hashing.sha512(jar)));
    }

    @Test
    void allDedicatedServerEntrypointsAreExcludedButUnknownIsIncluded() throws IOException {
        Path server = jar("server.jar", "server");
        Path unknown = jar("unknown.jar", "unknown");
        var prepared = ServerManifest.prepare(gameDir, List.of(
                physical(server, List.of("server"), true,
                        ClientRequirementClassifier.EntrypointSide.DEDICATED_SERVER),
                physical(unknown, List.of("unknown"), false)),
                (item, hash) -> ServerManifest.Evidence.NONE);

        assertEquals(List.of("unknown"), prepared.manifest().entries().stream().map(entry -> entry.modId()).toList());
        assertEquals(ClientRequirement.UNKNOWN, prepared.classified().get(1).decision().requirement());
    }

    @Test
    void exactVersionEnvironmentCanExcludeButConflictingFmlEvidenceRequiresJar() throws IOException {
        Path optional = jar("optional.jar", "optional");
        Path conflict = jar("conflict.jar", "conflict");
        var evidence = new ClientRequirementClassifier.ModrinthEvidence(
                ClientRequirementClassifier.MatchKind.EXACT_SHA512,
                ClientRequirementClassifier.VersionEnvironment.SERVER_ONLY_CLIENT_OPTIONAL);
        var prepared = ServerManifest.prepare(gameDir, List.of(
                physical(optional, List.of("optional"), false),
                physical(conflict, List.of("conflict"), true,
                        ClientRequirementClassifier.EntrypointSide.DEFAULT)),
                (item, hash) -> new ServerManifest.Evidence(ClientRequirementClassifier.Override.NONE, evidence));

        assertEquals(List.of("conflict"), prepared.manifest().entries().stream().map(entry -> entry.modId()).toList());
        assertTrue(prepared.classified().stream().filter(item -> item.physicalJar().canonicalId().equals("conflict"))
                .findFirst().orElseThrow().decision().diagnostics().getFirst().contains("disagree"));
    }

    private Path jar(String name, String content) throws IOException {
        Path mods = gameDir.resolve("mods");
        Files.createDirectories(mods);
        return Files.writeString(mods.resolve(name), content);
    }

    private static NeoForgePhysicalDiscovery.PhysicalJar physical(Path jar, List<String> ids, boolean complete,
            ClientRequirementClassifier.EntrypointSide... entrypoints) {
        return new NeoForgePhysicalDiscovery.PhysicalJar(jar, ids, ids.getFirst(), ids.getFirst(), "1",
                new ClientRequirementClassifier.FmlEvidence(complete, List.of(entrypoints)));
    }
}
