package dev.modsbyfox.jane.neoforge;

import dev.modsbyfox.jane.core.Hashing;
import dev.modsbyfox.jane.core.ManifestEntry;
import dev.modsbyfox.jane.core.PathSafety;
import dev.modsbyfox.jane.core.RequiredManifest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Builds one Protocol 3 entry per required top-level physical JAR. */
public final class ServerManifest {
    private static final Logger LOGGER = LoggerFactory.getLogger("jane");

    public record Evidence(ClientRequirementClassifier.Override override,
                           ClientRequirementClassifier.ModrinthEvidence modrinth) {
        public static final Evidence NONE = new Evidence(ClientRequirementClassifier.Override.NONE, null);
    }

    @FunctionalInterface
    public interface EvidenceSource {
        Evidence forJar(NeoForgePhysicalDiscovery.PhysicalJar jar, String sha512) throws IOException;
    }

    public record Classified(NeoForgePhysicalDiscovery.PhysicalJar physicalJar,
                             ClientRequirementClassifier.Result decision, long size, String sha512) { }

    public record Prepared(RequiredManifest manifest, List<Classified> classified, Map<String, Path> filesByHash) {
        public Prepared {
            classified = List.copyOf(classified);
            filesByHash = Map.copyOf(filesByHash);
        }
    }

    private ServerManifest() { }

    public static Prepared prepare(Path gameDir) throws IOException {
        JaneRequirementConfig config = JaneRequirementConfig.read(gameDir);
        return prepare(gameDir, NeoForgePhysicalDiscovery.discover(gameDir),
                (jar, sha512) -> new Evidence(config.forJar(jar), null));
    }

    public static Prepared prepare(Path gameDir, List<NeoForgePhysicalDiscovery.PhysicalJar> jars,
                                   EvidenceSource evidenceSource) throws IOException {
        List<ManifestEntry> entries = new ArrayList<>();
        List<Classified> classified = new ArrayList<>();
        Map<String, Path> filesByHash = new HashMap<>();
        Set<Path> seenFiles = new HashSet<>();
        for (NeoForgePhysicalDiscovery.PhysicalJar physical : jars.stream()
                .sorted(Comparator.comparing(NeoForgePhysicalDiscovery.PhysicalJar::canonicalId)).toList()) {
            if (physical.modIds().contains("jane")) {
                if (physical.modIds().size() > 1)
                    throw new IOException("Jane is bundled with other mod IDs in one physical JAR");
                continue;
            }
            Path jar = PathSafety.existingJarInMods(gameDir, physical.jar());
            if (!seenFiles.add(jar)) throw new IOException("Duplicate physical mod JAR: " + jar.getFileName());
            long size = Files.size(jar);
            if (size <= 0 || !Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Invalid physical mod JAR: " + jar.getFileName());
            FileTime modified = Files.getLastModifiedTime(jar);
            String sha512 = Hashing.sha512(jar);
            if (Files.size(jar) != size || !Files.getLastModifiedTime(jar).equals(modified))
                throw new IOException("Physical mod JAR changed while hashing: " + jar.getFileName());
            Evidence evidence = evidenceSource.forJar(physical, sha512);
            if (evidence == null) evidence = Evidence.NONE;
            ClientRequirementClassifier.Result decision = ClientRequirementClassifier.classify(
                    new ClientRequirementClassifier.Input(evidence.override(), physical.fmlEvidence(), evidence.modrinth()));
            classified.add(new Classified(physical, decision, size, sha512));
            for (String diagnostic : decision.diagnostics())
                LOGGER.warn("Jane mod={} file={} classification: {}", physical.canonicalId(), jar.getFileName(), diagnostic);
            if (!decision.includeInClientManifest()) {
                LOGGER.info("Jane mod={} file={} client requirement=NOT_REQUIRED", physical.canonicalId(), jar.getFileName());
                continue;
            }
            if (size > ManifestEntry.MAX_FILE_SIZE)
                throw new IOException("Required physical mod JAR exceeds Protocol 3 size limit: " + jar.getFileName());
            try {
                entries.add(new ManifestEntry(physical.canonicalId(), physical.displayName(), physical.version(),
                        size, sha512));
            } catch (IllegalArgumentException exception) {
                throw new IOException("Invalid required mod metadata: " + jar.getFileName(), exception);
            }
            if (entries.size() > RequiredManifest.MAX_ENTRIES)
                throw new IOException("Required physical mod count exceeds Protocol 3 limit");
            filesByHash.put(sha512, jar);
            LOGGER.info("Jane mod={} file={} client requirement={} hash={}", physical.canonicalId(),
                    jar.getFileName(), decision.requirement(), sha512.substring(0, 12));
        }
        try {
            return new Prepared(new RequiredManifest(RequiredManifest.PROTOCOL, entries), classified, filesByHash);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid physical required manifest", exception);
        }
    }
}
