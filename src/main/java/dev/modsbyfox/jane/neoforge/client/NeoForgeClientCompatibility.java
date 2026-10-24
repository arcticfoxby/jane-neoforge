package dev.modsbyfox.jane.neoforge.client;

import dev.modsbyfox.jane.core.ClientCompatibilityReport;
import dev.modsbyfox.jane.core.Comparison;
import dev.modsbyfox.jane.core.Hashing;
import dev.modsbyfox.jane.core.ManifestEntry;
import dev.modsbyfox.jane.core.PathSafety;
import dev.modsbyfox.jane.core.RequiredManifest;
import dev.modsbyfox.jane.neoforge.ClientRequirementClassifier;
import dev.modsbyfox.jane.neoforge.NeoForgePhysicalDiscovery;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Classifies client extras from the same top-level FML snapshot used for required comparison. */
public final class NeoForgeClientCompatibility {
    private static final Logger LOGGER = LoggerFactory.getLogger("jane");

    private NeoForgeClientCompatibility() { }

    public static ClientCompatibilityReport classify(Path gameDir, RequiredManifest manifest,
            List<NeoForgePhysicalDiscovery.PhysicalJar> jars) {
        return classify(gameDir, manifest, jars, jar -> {
            Path safe = PathSafety.existingJarInMods(gameDir, jar);
            long size = Files.size(safe);
            FileTime modified = Files.getLastModifiedTime(safe);
            String hash = Hashing.sha512(safe);
            if (Files.size(safe) != size || !Files.getLastModifiedTime(safe).equals(modified))
                throw new IOException("Client extra JAR changed while hashing");
            return hash;
        });
    }

    static ClientCompatibilityReport classify(Path gameDir, RequiredManifest manifest,
            List<NeoForgePhysicalDiscovery.PhysicalJar> jars, Comparison.FileHasher hasher) {
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
            boolean serverOnly = explicitlyServerOnly(jar.fmlEvidence());
            String hash = null;
            if (clientOnly) {
                try {
                    String candidate = hasher.hash(jar.jar());
                    if (candidate != null && candidate.matches("[0-9a-f]{128}")) hash = candidate;
                } catch (IOException | IllegalArgumentException exception) {
                    // Keep the extra visible; an unverified JAR cannot be selected for safe disable.
                    LOGGER.warn("Could not verify client-only extra {}: {}", jar.canonicalId(),
                            exception.getClass().getSimpleName());
                }
            }
            ClientCompatibilityReport.Kind kind = clientOnly
                    ? ClientCompatibilityReport.Kind.EXPLICIT_CLIENT
                    : serverOnly ? ClientCompatibilityReport.Kind.EXPLICIT_SERVER
                    : ClientCompatibilityReport.Kind.OTHER_EXTRA;
            ClientCompatibilityReport.ExtraMod extra = new ClientCompatibilityReport.ExtraMod(
                    jar.canonicalId(), blankFallback(jar.displayName(), jar.canonicalId()),
                    blankFallback(jar.version(), "unknown"), jar.jar().getFileName().toString(),
                    hash, kind, jar.jar());
            if (clientOnly) explicit.add(extra);
            else other.add(extra);
        }
        return new ClientCompatibilityReport(explicit, other, ClientCompatibilityReport.fingerprint(explicit));
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
