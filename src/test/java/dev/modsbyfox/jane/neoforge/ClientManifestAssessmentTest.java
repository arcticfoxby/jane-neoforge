package dev.modsbyfox.jane.neoforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.modsbyfox.jane.core.Comparison;
import dev.modsbyfox.jane.core.Hashing;
import dev.modsbyfox.jane.core.ManifestEntry;
import dev.modsbyfox.jane.core.RequiredManifest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientManifestAssessmentTest {
    @TempDir Path gameDir;

    @Test
    void equalVersionWithWrongSha512DoesNotPass() throws IOException {
        Path jar = jar("required.jar", "aaaa");
        String otherHash = Hashing.sha512(jar("other.jar", "bbbb"));
        RequiredManifest manifest = manifest("required", "1", 4, otherHash);
        var local = physical(jar, "required", "1");

        var result = ClientManifestAssessment.assess(gameDir, manifest, List.of(local));

        assertEquals(Comparison.Status.HASH_MISMATCH, result.getFirst().status());
        assertTrue(!Comparison.passed(result));
    }

    @Test
    void wrongPhysicalSizeFailsClosed() throws IOException {
        Path jar = jar("required.jar", "too-large");
        RequiredManifest manifest = manifest("required", "1", 4, Hashing.sha512(jar));

        var result = ClientManifestAssessment.assess(gameDir, manifest, List.of(physical(jar, "required", "1")));

        assertEquals(Comparison.Status.FILE_ERROR, result.getFirst().status());
    }

    private Path jar(String name, String content) throws IOException {
        Path mods = gameDir.resolve("mods");
        Files.createDirectories(mods);
        return Files.writeString(mods.resolve(name), content);
    }

    private static RequiredManifest manifest(String id, String version, long size, String hash) {
        return new RequiredManifest(RequiredManifest.PROTOCOL,
                List.of(new ManifestEntry(id, id, version, size, hash)));
    }

    private static NeoForgePhysicalDiscovery.PhysicalJar physical(Path jar, String id, String version) {
        return new NeoForgePhysicalDiscovery.PhysicalJar(jar, List.of(id), id, id, version,
                new ClientRequirementClassifier.FmlEvidence(false, List.of()));
    }
}
