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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Builds one Protocol 3 entry per required top-level physical JAR. */
public final class ServerManifest {
    private static final Logger LOGGER = LoggerFactory.getLogger("jane");
    private static final PhysicalJarAnalysisCache<JarAnalysisResult> ANALYSIS_CACHE =
            new PhysicalJarAnalysisCache<>(256);
    private static final Map<String, Boolean> LOGGED_DECISIONS = new LinkedHashMap<>();
    private static final int MAX_LOGGED_DECISIONS = 512;

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
                (jar, sha512) -> new Evidence(config.forJar(jar), null), config.identity());
    }

    public static Prepared prepare(Path gameDir, List<NeoForgePhysicalDiscovery.PhysicalJar> jars,
                                   EvidenceSource evidenceSource) throws IOException {
        return prepare(gameDir, jars, evidenceSource, JaneRequirementConfig.read(gameDir).identity());
    }

    static Prepared prepare(Path gameDir, List<NeoForgePhysicalDiscovery.PhysicalJar> jars,
                            EvidenceSource evidenceSource, String configIdentity) throws IOException {
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
            JarAnalysisResult analysis;
            try {
                String evidenceIdentity = analysisIdentity(configIdentity, physical);
                analysis = ANALYSIS_CACHE.getOrAnalyze(gameDir, jar, sha512, size, modified,
                        evidenceIdentity, NeoForgeJarAnalyzer.RULE_VERSION,
                        canonical -> NeoForgeJarAnalyzer.analyze(gameDir, physical));
            } catch (RuntimeException failure) {
                throw new IOException("Physical mod JAR analysis failed: " + jar.getFileName(), failure);
            }
            Evidence evidence = evidenceSource.forJar(physical, sha512);
            if (evidence == null) evidence = Evidence.NONE;
            ClientRequirementClassifier.Result decision = ClientRequirementClassifier.classify(
                    new ClientRequirementClassifier.Input(evidence.override(), physical.fmlEvidence(),
                            evidence.modrinth(), analysis));
            classified.add(new Classified(physical, decision, size, sha512));
            logDecision(physical, jar, sha512, configIdentity, analysis, decision);
            if (!decision.includeInClientManifest()) {
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
        }
        try {
            return new Prepared(new RequiredManifest(RequiredManifest.PROTOCOL, entries), classified, filesByHash);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid physical required manifest", exception);
        }
    }

    /** Cache observations are tied to both file bytes and the FML snapshot they interpreted. */
    private static String analysisIdentity(String configIdentity,
                                           NeoForgePhysicalDiscovery.PhysicalJar physical) {
        StringBuilder data = new StringBuilder();
        appendIdentityPart(data, configIdentity);
        appendIdentityPart(data, physical.canonicalId());
        appendIdentityPart(data, physical.displayName());
        appendIdentityPart(data, physical.version());
        data.append(physical.fmlEvidence().complete() ? '1' : '0');
        data.append(physical.modIds().size()).append(':');
        for (String modId : physical.modIds()) appendIdentityPart(data, modId);
        data.append(physical.fmlEvidence().entrypoints().size()).append(':');
        for (ClientRequirementClassifier.EntrypointSide side : physical.fmlEvidence().entrypoints())
            appendIdentityPart(data, side.name());
        data.append(physical.dependencyDescriptions().size()).append(':');
        for (String dependency : physical.dependencyDescriptions()) appendIdentityPart(data, dependency);
        return Hashing.sha256(data.toString());
    }

    private static void appendIdentityPart(StringBuilder data, String value) {
        data.append(value.length()).append(':').append(value);
    }

    private static void logDecision(NeoForgePhysicalDiscovery.PhysicalJar physical, Path jar, String sha512,
                                    String configIdentity, JarAnalysisResult analysis,
                                    ClientRequirementClassifier.Result decision) {
        String signature = jar + "|" + sha512 + "|" + configIdentity + "|"
                + NeoForgeJarAnalyzer.RULE_VERSION + "|" + decision.requirement() + "|" + decision.reason();
        synchronized (LOGGED_DECISIONS) {
            if (LOGGED_DECISIONS.putIfAbsent(signature, Boolean.TRUE) != null) return;
            if (LOGGED_DECISIONS.size() > MAX_LOGGED_DECISIONS)
                LOGGED_DECISIONS.remove(LOGGED_DECISIONS.keySet().iterator().next());
        }
        LOGGER.info("Jane mod={} file={} ids={} client requirement={} manifest={} reason={} hash={}",
                physical.canonicalId(), jar.getFileName(), physical.modIds(), decision.requirement(),
                decision.includeInClientManifest() ? "included" : "excluded",
                decision.reason(), sha512.substring(0, 12));
        LOGGER.debug("Jane mod={} analysis complete={} classes={} optionalPayloads={} requiredPayloads={}",
                physical.canonicalId(), decision.analysisComplete(), analysis.scannedClasses(),
                analysis.optionalPayloads(), analysis.requiredPayloads());
        for (ClientRequirementClassifier.DecisionEvidence item : decision.evidence())
            LOGGER.debug("Jane mod={} evidence source={} type={} outcome={} complete={} detail={}",
                    physical.canonicalId(), item.source(), item.type(), item.outcome(), item.complete(), item.detail());
        for (String diagnostic : decision.diagnostics()) {
            if (!diagnostic.equals(decision.reason()))
                LOGGER.warn("Jane mod={} file={} classification: {}", physical.canonicalId(),
                        jar.getFileName(), diagnostic);
        }
    }
}
