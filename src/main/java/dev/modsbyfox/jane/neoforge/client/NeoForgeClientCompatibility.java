package dev.modsbyfox.jane.neoforge.client;

import dev.modsbyfox.jane.core.ClientCompatibilityReport;
import dev.modsbyfox.jane.core.ClientCleanupGraph;
import dev.modsbyfox.jane.core.Comparison;
import dev.modsbyfox.jane.core.Hashing;
import dev.modsbyfox.jane.core.ManifestEntry;
import dev.modsbyfox.jane.core.PathSafety;
import dev.modsbyfox.jane.core.RequiredManifest;
import dev.modsbyfox.jane.neoforge.ClientRequirementClassifier;
import dev.modsbyfox.jane.neoforge.JarAnalysisResult;
import dev.modsbyfox.jane.neoforge.JarEvidence;
import dev.modsbyfox.jane.neoforge.NeoForgeCleanupGraph;
import dev.modsbyfox.jane.neoforge.NeoForgeJarAnalyzer;
import dev.modsbyfox.jane.neoforge.NeoForgePhysicalDiscovery;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Classifies client extras from the same top-level FML snapshot used for required comparison. */
public final class NeoForgeClientCompatibility {
    private static final Logger LOGGER = LoggerFactory.getLogger("jane");

    @FunctionalInterface
    interface ServerJarVerifier {
        String verifiedHash(NeoForgePhysicalDiscovery.PhysicalJar jar) throws IOException;
    }

    private NeoForgeClientCompatibility() { }

    public static ClientCompatibilityReport classify(Path gameDir, RequiredManifest manifest,
            List<NeoForgePhysicalDiscovery.PhysicalJar> jars) {
        ClientCleanupGraph cleanupGraph = NeoForgeCleanupGraph.fromDiscoveredJars(gameDir, jars);
        return classify(gameDir, manifest, jars, jar -> {
            Path safe = PathSafety.existingJarInMods(gameDir, jar);
            long size = Files.size(safe);
            FileTime modified = Files.getLastModifiedTime(safe);
            String hash = Hashing.sha512(safe);
            if (Files.size(safe) != size || !Files.getLastModifiedTime(safe).equals(modified))
                throw new IOException("Client extra JAR changed while hashing");
            return hash;
        }, cleanupGraph, jar -> verifyCurrentServerEntrypoints(gameDir, jar));
    }

    /** Bind FML's cached side evidence to the exact bytes of the current physical JAR. */
    private static String verifyCurrentServerEntrypoints(Path gameDir,
            NeoForgePhysicalDiscovery.PhysicalJar jar) throws IOException {
        Path safe = PathSafety.existingJarInMods(gameDir, jar.jar());
        BasicFileAttributes before = Files.readAttributes(safe, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        String beforeHash = Hashing.sha512(safe);
        JarAnalysisResult analysis = NeoForgeJarAnalyzer.analyze(gameDir, jar);
        PathSafety.rejectReparsePoint(safe);
        BasicFileAttributes after = Files.readAttributes(safe, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        String afterHash = Hashing.sha512(safe);
        if (!beforeHash.equals(afterHash) || before.size() != after.size()
                || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !Objects.equals(before.fileKey(), after.fileKey()))
            throw new IOException("Client extra JAR changed during entrypoint inspection");
        return analysis.complete() && analysis.has(JarEvidence.Kind.ENTRYPOINT_MATCH)
                ? afterHash : null;
    }

    static ClientCompatibilityReport classify(Path gameDir, RequiredManifest manifest,
            List<NeoForgePhysicalDiscovery.PhysicalJar> jars, Comparison.FileHasher hasher) {
        return classify(gameDir, manifest, jars, hasher, ClientCleanupGraph.unavailable());
    }

    static ClientCompatibilityReport classify(Path gameDir, RequiredManifest manifest,
            List<NeoForgePhysicalDiscovery.PhysicalJar> jars, Comparison.FileHasher hasher,
            ClientCleanupGraph cleanupGraph) {
        return classify(gameDir, manifest, jars, hasher, cleanupGraph,
                jar -> hasher.hash(jar.jar()));
    }

    /** Package-private evidence seam; production always scans the current JAR bytes. */
    static ClientCompatibilityReport classify(Path gameDir, RequiredManifest manifest,
            List<NeoForgePhysicalDiscovery.PhysicalJar> jars, Comparison.FileHasher hasher,
            ClientCleanupGraph cleanupGraph, ServerJarVerifier serverVerifier) {
        Set<String> requiredIds = new HashSet<>();
        manifest.entries().forEach(entry -> requiredIds.add(entry.modId()));
        List<ClientCompatibilityReport.ExtraMod> explicit = new ArrayList<>();
        List<ClientCompatibilityReport.ExtraMod> other = new ArrayList<>();
        for (NeoForgePhysicalDiscovery.PhysicalJar jar : jars) {
            if (jar.modIds().stream().anyMatch(requiredIds::contains)
                    || jar.modIds().contains("jane")) continue;
            if (!ManifestEntry.validId(jar.canonicalId())) {
                LOGGER.warn("Skipping client extra with invalid FML mod ID");
                continue;
            }
            boolean clientOnly = explicitlyClientOnly(jar.fmlEvidence());
            boolean serverOnly = !clientOnly && explicitlyServerOnly(jar.fmlEvidence());
            String hash = null;
            boolean staleFile = false;
            if (clientOnly) {
                try {
                    String candidate = hasher.hash(jar.jar());
                    if (candidate != null && candidate.matches("[0-9a-f]{128}")) hash = candidate;
                } catch (IOException | IllegalArgumentException exception) {
                    // Keep the extra visible; an unverified JAR cannot be selected for safe disable.
                    LOGGER.warn("Could not verify client extra {}: {}", jar.canonicalId(),
                            exception.getClass().getSimpleName());
                }
            }
            if (serverOnly) {
                try {
                    String candidate = serverVerifier.verifiedHash(jar);
                    if (candidate != null && candidate.matches("[0-9a-f]{128}")) hash = candidate;
                    else serverOnly = false;
                } catch (IOException | RuntimeException exception) {
                    serverOnly = false;
                    LOGGER.warn("Could not prove current server-only entrypoint for {}: {}",
                            jar.canonicalId(), exception.getClass().getSimpleName());
                }
            }
            if (serverOnly && hash != null && cleanupGraph.complete()) {
                ClientCleanupGraph.PhysicalJar graphJar = cleanupGraph.jars().stream()
                        .filter(physical -> physical.filename().equals(
                                jar.jar().getFileName().toString()))
                        .findFirst().orElse(null);
                String observed = graphJar == null ? null : graphJar.observedSha512();
                if (observed == null || !hash.equals(observed)) {
                    hash = null;
                    staleFile = true;
                }
            }
            ClientCompatibilityReport.Kind kind = clientOnly
                    ? ClientCompatibilityReport.Kind.EXPLICIT_CLIENT
                    : serverOnly ? ClientCompatibilityReport.Kind.EXPLICIT_SERVER
                    : ClientCompatibilityReport.Kind.OTHER_EXTRA;
            ClientCompatibilityReport.ExtraMod extra = new ClientCompatibilityReport.ExtraMod(
                    jar.canonicalId(), blankFallback(jar.displayName(), jar.canonicalId()),
                    blankFallback(jar.version(), "unknown"), jar.jar().getFileName().toString(),
                    hash, kind, jar.jar(), jar.modIds(), false,
                    serverOnly ? staleFile ? "STALE_FILE"
                            : hash == null ? "UNVERIFIED_HASH" : "DEPENDENCY_CHECK_PENDING" : null);
            if (clientOnly) explicit.add(extra);
            else other.add(extra);
        }
        Set<String> hashedServerCandidates = new HashSet<>();
        for (ClientCompatibilityReport.ExtraMod extra : other)
            if (extra.kind() == ClientCompatibilityReport.Kind.EXPLICIT_SERVER && extra.verified())
                hashedServerCandidates.add(extra.filename());
        Set<String> defaultSelected = cleanupGraph.safeDefaultSelection(hashedServerCandidates);
        List<ClientCompatibilityReport.ExtraMod> assessed = new ArrayList<>();
        for (ClientCompatibilityReport.ExtraMod extra : other) {
            if (extra.kind() != ClientCompatibilityReport.Kind.EXPLICIT_SERVER) {
                assessed.add(extra);
                continue;
            }
            boolean eligible = defaultSelected.contains(extra.filename());
            String blockedReason = eligible ? null
                    : "STALE_FILE".equals(extra.cleanupBlockedReason()) ? "STALE_FILE"
                    : extra.sha512() == null ? "UNVERIFIED_HASH"
                    : cleanupBlockedReason(cleanupGraph, defaultSelected, extra.filename());
            assessed.add(new ClientCompatibilityReport.ExtraMod(extra.modId(), extra.displayName(),
                    extra.version(), extra.filename(), extra.sha512(), extra.kind(), extra.jar(),
                    extra.containedModIds(), eligible, blockedReason));
        }
        return new ClientCompatibilityReport(explicit, assessed,
                ClientCompatibilityReport.fingerprint(explicit), cleanupGraph);
    }

    private static String cleanupBlockedReason(ClientCleanupGraph graph, Set<String> defaultSelected,
            String filename) {
        Set<String> attempted = new HashSet<>(defaultSelected);
        attempted.add(filename);
        ClientCleanupGraph.Decision decision = graph.evaluate(attempted).byFilename().get(filename);
        return decision != null && decision.block() != null
                ? decision.block().reason().name() : "UNKNOWN_METADATA";
    }

    static boolean explicitlyClientOnly(ClientRequirementClassifier.FmlEvidence evidence) {
        return evidence.complete() && !evidence.entrypoints().isEmpty()
                && evidence.entrypoints().stream().allMatch(side ->
                        side == ClientRequirementClassifier.EntrypointSide.CLIENT);
    }

    static boolean explicitlyServerOnly(ClientRequirementClassifier.FmlEvidence evidence) {
        return evidence.complete() && !evidence.entrypoints().isEmpty()
                && evidence.entrypoints().stream().allMatch(side ->
                        side == ClientRequirementClassifier.EntrypointSide.DEDICATED_SERVER);
    }

    private static String blankFallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
