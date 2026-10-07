package dev.modsbyfox.jane.neoforge;

import dev.modsbyfox.jane.core.Comparison;
import dev.modsbyfox.jane.core.Hashing;
import dev.modsbyfox.jane.core.ManifestEntry;
import dev.modsbyfox.jane.core.PathSafety;
import dev.modsbyfox.jane.core.RequiredManifest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Compares a server manifest against this client's top-level physical JARs. */
public final class ClientManifestAssessment {
    private ClientManifestAssessment() { }

    public static List<Comparison.Result> assess(Path gameDir, RequiredManifest manifest) throws IOException {
        return assess(gameDir, manifest, NeoForgePhysicalDiscovery.discover(gameDir));
    }

    /** Assesses an FML discovery snapshot so the file hashing may run off the game thread. */
    public static List<Comparison.Result> assess(Path gameDir, RequiredManifest manifest,
                                                  List<NeoForgePhysicalDiscovery.PhysicalJar> physicalJars) throws IOException {
        Map<String, List<Comparison.LocalMod>> candidates = new HashMap<>();
        Map<String, Long> expectedSizeById = new HashMap<>();
        for (ManifestEntry entry : manifest.entries()) expectedSizeById.put(entry.modId(), entry.fileSize());
        Map<Path, Long> expectedSizeByJar = new HashMap<>();
        for (NeoForgePhysicalDiscovery.PhysicalJar physical : physicalJars) {
            Path safe = PathSafety.existingJarInMods(gameDir, physical.jar());
            candidates.computeIfAbsent(physical.canonicalId(), ignored -> new ArrayList<>())
                    .add(new Comparison.LocalMod(physical.canonicalId(), physical.version(), safe));
            Long expectedSize = expectedSizeById.get(physical.canonicalId());
            if (expectedSize != null) {
                Long previous = expectedSizeByJar.putIfAbsent(safe, expectedSize);
                if (previous != null && !previous.equals(expectedSize))
                    throw new IOException("Conflicting required sizes for one physical JAR");
            }
        }
        return Comparison.compareCandidates(manifest, candidates, jar -> {
            Path safe = PathSafety.existingJarInMods(gameDir, jar);
            long size = Files.size(safe);
            if (size <= 0 || (expectedSizeByJar.containsKey(safe) && size != expectedSizeByJar.get(safe)))
                throw new IOException("Physical JAR size differs from required manifest");
            FileTime modified = Files.getLastModifiedTime(safe);
            String hash = Hashing.sha512(safe);
            if (Files.size(safe) != size || !Files.getLastModifiedTime(safe).equals(modified))
                throw new IOException("Physical JAR changed while hashing");
            return hash;
        });
    }
}
