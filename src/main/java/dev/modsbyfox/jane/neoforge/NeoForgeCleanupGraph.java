package dev.modsbyfox.jane.neoforge;

import dev.modsbyfox.jane.core.ClientCleanupGraph;
import dev.modsbyfox.jane.core.Hashing;
import dev.modsbyfox.jane.core.ManifestEntry;
import dev.modsbyfox.jane.core.PathSafety;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforgespi.language.IModFileInfo;
import org.apache.maven.artifact.versioning.VersionRange;

/** Converts FML's current resolved metadata into a local-only cleanup dependency graph. */
public final class NeoForgeCleanupGraph {
    private NeoForgeCleanupGraph() {}

    /** No checked failure: uncertainty leaves every candidate ineligible. Run off the render thread. */
    public static ClientCleanupGraph fromDiscoveredJars(Path gameDir,
            List<NeoForgePhysicalDiscovery.PhysicalJar> jars) {
        try {
            ModList modList = ModList.get();
            if (gameDir == null || jars == null || modList == null)
                return ClientCleanupGraph.unavailable();
            List<ClientCleanupGraph.PhysicalJar> physical = new ArrayList<>();
            Set<String> physicalIds = new HashSet<>();
            for (NeoForgePhysicalDiscovery.PhysicalJar jar : jars) {
                Objects.requireNonNull(jar, "FML physical JAR");
                String observedHash = observedCurrentJarHash(jar);
                if (observedHash == null) return ClientCleanupGraph.unavailable();
                String filename = jar.jar().getFileName().toString();
                physical.add(new ClientCleanupGraph.PhysicalJar(filename, jar.modIds(),
                        jar.dependencies(), jar.dependencyMetadataComplete(), observedHash));
                physicalIds.addAll(jar.modIds());
            }
            IModFileInfo janeInfo = modList.getModFileById("jane");
            if (janeInfo == null || janeInfo.getFile() == null
                    || janeInfo.getMods() == null || janeInfo.getMods().size() != 1
                    || !"jane".equals(janeInfo.getMods().getFirst().getModId()))
                return ClientCleanupGraph.unavailable();
            Path janePath = janeInfo.getFile().getFilePath();
            if (janePath == null) return ClientCleanupGraph.unavailable();
            Path lexicalMods = gameDir.toAbsolutePath().normalize().resolve("mods");
            if (lexicalMods.equals(janePath.toAbsolutePath().normalize().getParent())) {
                Path verified = PathSafety.existingJarInMods(gameDir, janePath);
                IModInfo janeMod = janeInfo.getMods().getFirst();
                NeoForgePhysicalDiscovery.DependencyFacts facts =
                        NeoForgePhysicalDiscovery.readDependencies(janeMod);
                NeoForgePhysicalDiscovery.PhysicalJar janePhysical =
                        new NeoForgePhysicalDiscovery.PhysicalJar(verified, List.of("jane"),
                                "jane", janeMod.getDisplayName(),
                                janeMod.getVersion().toString(),
                                new ClientRequirementClassifier.FmlEvidence(false, List.of()),
                                facts.descriptions(), facts.dependencies(), facts.complete());
                String observedHash = observedCurrentJarHash(janePhysical);
                if (observedHash == null)
                    return ClientCleanupGraph.unavailable();
                physical.add(new ClientCleanupGraph.PhysicalJar(
                        verified.getFileName().toString(), List.of("jane"),
                        facts.dependencies(), facts.complete(), observedHash));
                physicalIds.add("jane");
            }
            List<ClientCleanupGraph.RetainedMod> external = new ArrayList<>();
            List<IModInfo> mods = modList.getMods();
            if (mods == null) return ClientCleanupGraph.unavailable();
            for (IModInfo mod : mods) {
                if (mod == null || !ClientCleanupGraph.validModId(mod.getModId()))
                    return ClientCleanupGraph.unavailable();
                if (physicalIds.contains(mod.getModId())) continue;
                NeoForgePhysicalDiscovery.DependencyFacts facts =
                        NeoForgePhysicalDiscovery.readDependencies(mod);
                external.add(new ClientCleanupGraph.RetainedMod(mod.getModId(),
                        facts.dependencies(), facts.complete()));
            }
            return new ClientCleanupGraph(physical, external, true);
        } catch (IOException | RuntimeException incomplete) {
            return ClientCleanupGraph.unavailable();
        }
    }

    /**
     * FML's metadata snapshot has no source-file digest. Check current disk metadata
     * against it before using the snapshot to authorize any future removal.
     */
    static boolean corroboratesCurrentJar(NeoForgePhysicalDiscovery.PhysicalJar jar)
            throws IOException {
        return observedCurrentJarHash(jar) != null;
    }

    private static String observedCurrentJarHash(NeoForgePhysicalDiscovery.PhysicalJar jar)
            throws IOException {
        if (!jar.dependencyMetadataComplete()) return null;
        Path path = jar.jar();
        PathSafety.rejectReparsePoint(path);
        BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.size() <= 0
                || before.size() > ManifestEntry.MAX_FILE_SIZE) return null;
        String beforeHash = Hashing.sha512(path);
        ClientCleanupGraph.PhysicalJar disk = NeoForgeStagedCleanupGraph.inspectCurrent(path,
                path.getFileName().toString());
        String afterHash = Hashing.sha512(path);
        PathSafety.rejectReparsePoint(path);
        BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!after.isRegularFile() || before.size() != after.size()
                || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !Objects.equals(before.fileKey(), after.fileKey())
                || !beforeHash.equals(afterHash)) return null;
        Set<String> fmlIds = new HashSet<>(jar.modIds());
        if (fmlIds.size() != jar.modIds().size()
                || !fmlIds.equals(Set.copyOf(disk.modIds()))) return null;
        return dependencyCounts(jar.dependencies()).equals(dependencyCounts(disk.dependencies()))
                ? beforeHash : null;
    }

    private static Map<ClientCleanupGraph.Dependency, Integer> dependencyCounts(
            List<ClientCleanupGraph.Dependency> dependencies) throws IOException {
        Map<ClientCleanupGraph.Dependency, Integer> counts = new HashMap<>();
        for (ClientCleanupGraph.Dependency dependency : dependencies) {
            String range = dependency.versionRange();
            try {
                range = range.isBlank() ? "" : VersionRange.createFromVersionSpec(range).toString();
            } catch (Exception malformed) {
                throw new IOException("FML dependency range is unresolved", malformed);
            }
            ClientCleanupGraph.Dependency comparable = new ClientCleanupGraph.Dependency(
                    dependency.ownerModId(), dependency.targetModId(), dependency.type(),
                    dependency.side(), range);
            counts.merge(comparable, 1, Integer::sum);
        }
        return counts;
    }
}
