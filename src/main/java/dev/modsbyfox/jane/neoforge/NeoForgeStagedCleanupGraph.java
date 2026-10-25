package dev.modsbyfox.jane.neoforge;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import dev.modsbyfox.jane.core.ClientCleanupGraph;
import dev.modsbyfox.jane.core.Hashing;
import dev.modsbyfox.jane.core.PathSafety;
import dev.modsbyfox.jane.core.UpdatePlan;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.apache.maven.artifact.versioning.VersionRange;

/**
 * Builds a fail-closed future dependency snapshot from verified staged install JARs.
 * This is a bounded, read-only inspection; it must run off the render thread.
 */
public final class NeoForgeStagedCleanupGraph {
    private static final int MAX_ENTRIES = 50_000;
    private static final int MAX_METADATA_BYTES = 256 * 1024;
    private static final int MAX_MOD_IDS = 512;
    private static final int MAX_DEPENDENCIES = 4096;
    private static final String MODS_TOML = "META-INF/neoforge.mods.toml";
    // JavaFML's Mod IDs are narrower than resource namespaces and graph fixture IDs.
    private static final Pattern FML_MOD_ID = Pattern.compile("[a-z][a-z0-9_]{1,63}");

    private NeoForgeStagedCleanupGraph() {}

    /** Removes replaced files from the live graph, then adds exact staged future files. */
    public static ClientCleanupGraph merge(Path gameDir, ClientCleanupGraph liveGraph,
                                           UpdatePlan installPlan) throws IOException {
        if (gameDir == null || liveGraph == null || installPlan == null || !liveGraph.complete())
            throw new IOException("Current dependency snapshot is unavailable");
        validateGraph(liveGraph);
        if (installPlan.operations().isEmpty()) return liveGraph;
        Path staging = PathSafety.janeDirectoryPath(gameDir, "staging", installPlan.syncId());
        if (!Files.isDirectory(staging, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Staged JAR directory is missing");
        List<ClientCleanupGraph.PhysicalJar> future = new ArrayList<>(liveGraph.jars());
        for (UpdatePlan.Operation operation : installPlan.operations()) {
            if (operation.kind() == UpdatePlan.Kind.REPLACE) {
                boolean found = future.removeIf(jar ->
                        jar.filename().equalsIgnoreCase(operation.oldFile()));
                if (!found) throw new IOException("Replaced physical JAR is absent from dependency snapshot");
            }
        }
        for (UpdatePlan.Operation operation : installPlan.operations()) {
            Path staged = staging.resolve(PathSafety.safeExistingJarName(operation.newFile()));
            if (!staged.normalize().getParent().equals(staging.normalize()))
                throw new IOException("Staged JAR escapes workspace");
            PathSafety.rejectReparsePoint(staged);
            if (!Files.isRegularFile(staged, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Staged JAR is missing or is not a regular file");
            long size = Files.size(staged);
            FileTime modified = Files.getLastModifiedTime(staged, LinkOption.NOFOLLOW_LINKS);
            if (size != operation.size() || !operation.newHash().equals(Hashing.sha512(staged)))
                throw new IOException("Staged JAR does not match the required exact file");
            ClientCleanupGraph.PhysicalJar parsed = inspect(staged, operation.newFile());
            if (!parsed.modIds().contains(operation.modId()))
                throw new IOException("Staged physical JAR omits its Required Mod ID");
            if (Files.size(staged) != size
                    || !Files.getLastModifiedTime(staged, LinkOption.NOFOLLOW_LINKS).equals(modified)
                    || !operation.newHash().equals(Hashing.sha512(staged)))
                throw new IOException("Staged JAR changed during dependency inspection");
            if (future.stream().anyMatch(jar -> jar.filename().equalsIgnoreCase(parsed.filename())))
                throw new IOException("Staged JAR conflicts with a retained physical file");
            future.add(new ClientCleanupGraph.PhysicalJar(parsed.filename(), parsed.modIds(),
                    parsed.dependencies(), true, operation.newHash()));
        }
        ClientCleanupGraph merged = new ClientCleanupGraph(future, liveGraph.externalMods(), true);
        validateGraph(merged);
        return merged;
    }

    private static void validateGraph(ClientCleanupGraph graph) throws IOException {
        Set<String> futureIds = new HashSet<>();
        Set<String> futureNames = new HashSet<>();
        for (ClientCleanupGraph.PhysicalJar jar : graph.jars()) {
            if (!jar.metadataComplete()
                    || !futureNames.add(jar.filename().toLowerCase(Locale.ROOT)))
                throw new IOException("Future physical JAR metadata is incomplete or conflicting");
            for (String id : jar.modIds())
                if (!futureIds.add(id)) throw new IOException("Future physical Mod IDs conflict");
        }
        for (ClientCleanupGraph.RetainedMod mod : graph.externalMods()) {
            if (!mod.metadataComplete() || !futureIds.add(mod.modId()))
                throw new IOException("Future retained Mod metadata is incomplete or conflicting");
        }
    }

    static ClientCleanupGraph.PhysicalJar inspect(Path jar, String installedFilename) throws IOException {
        return inspect(jar, installedFilename, false);
    }

    static ClientCleanupGraph.PhysicalJar inspectCurrent(Path jar, String filename)
            throws IOException {
        return inspect(jar, filename, true);
    }

    private static ClientCleanupGraph.PhysicalJar inspect(Path jar, String installedFilename,
            boolean allowJane) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Set<String> seen = new HashSet<>();
            ZipEntry metadata = null;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            int count = 0;
            while (entries.hasMoreElements()) {
                if (++count > MAX_ENTRIES) throw new IOException("Staged JAR entry limit exceeded");
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!safeEntryName(name) || !seen.add(name.toLowerCase(Locale.ROOT)))
                    throw new IOException("Staged JAR contains unsafe or duplicate entries");
                if (name.toLowerCase(Locale.ROOT).endsWith(".jar")
                        || name.startsWith("META-INF/jarjar/"))
                    throw new IOException("Nested staged JAR needs separate FML analysis");
                if (MODS_TOML.equals(name)) metadata = entry;
            }
            if (metadata == null || metadata.getSize() > MAX_METADATA_BYTES)
                throw new IOException("Staged JavaFML metadata is missing or oversized");
            try (InputStream input = zip.getInputStream(metadata)) {
                byte[] bytes = input.readNBytes(MAX_METADATA_BYTES + 1);
                if (bytes.length > MAX_METADATA_BYTES || input.read() != -1)
                    throw new IOException("Staged JavaFML metadata exceeds size limit");
                if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xef
                        && (bytes[1] & 0xff) == 0xbb && (bytes[2] & 0xff) == 0xbf)
                    throw new IOException("Staged JavaFML metadata has an unsupported BOM");
                return parseToml(bytes, installedFilename, allowJane);
            }
        } catch (RuntimeException malformed) {
            throw new IOException("Staged JAR metadata cannot be resolved", malformed);
        }
    }

    private static ClientCleanupGraph.PhysicalJar parseToml(byte[] bytes, String filename,
            boolean allowJane)
            throws IOException {
        UnmodifiableConfig config;
        try (var reader = new InputStreamReader(new java.io.ByteArrayInputStream(bytes),
                StandardCharsets.UTF_8)) {
            config = TomlFormat.instance().createParser().parse(reader);
        } catch (RuntimeException malformed) {
            throw new IOException("Staged JavaFML TOML is invalid", malformed);
        }
        if (!"javafml".equals(stringOrDefault(config.get("modLoader"), "javafml")))
            throw new IOException("Staged Mod loader is not JavaFML");
        if (!(config.get("license") instanceof String license) || license.isBlank()
                || stringOrDefault(config.get("loaderVersion"), "") == null)
            throw new IOException("Staged JavaFML file metadata is incomplete");
        String loaderVersion = stringOrDefault(config.get("loaderVersion"), "");
        try {
            if (!loaderVersion.isBlank()) VersionRange.createFromVersionSpec(loaderVersion);
        } catch (Exception malformed) {
            throw new IOException("Staged JavaFML loader range is unresolved", malformed);
        }
        Object modsValue = config.get("mods");
        if (!(modsValue instanceof List<?> mods) || mods.isEmpty() || mods.size() > MAX_MOD_IDS)
            throw new IOException("Staged Mod ID list is incomplete");
        Set<String> ids = new HashSet<>();
        for (Object value : mods) {
            if (!(value instanceof UnmodifiableConfig mod)
                    || !(mod.get("modId") instanceof String id)
                    || !validFmlModId(id) || (!allowJane && "jane".equals(id))
                    || stringOrDefault(mod.get("version"), "1") == null
                    || stringOrDefault(mod.get("version"), "1").isBlank()
                    || stringOrDefault(mod.get("displayName"), id) == null
                    || stringOrDefault(mod.get("displayName"), id).isBlank()
                    || !ids.add(id))
                throw new IOException("Staged Mod ID declaration is unresolved");
        }
        List<ClientCleanupGraph.Dependency> dependencies = new ArrayList<>();
        Object dependencyValue = config.get("dependencies");
        if (dependencyValue != null) {
            if (!(dependencyValue instanceof UnmodifiableConfig root))
                throw new IOException("Staged dependency table is unresolved");
            for (UnmodifiableConfig.Entry ownerEntry : root.entrySet()) {
                String ownerId = ownerEntry.getKey();
                if (!ids.contains(ownerId) || !(ownerEntry.getValue() instanceof List<?> declarations))
                    throw new IOException("Staged dependency owner is unresolved");
                for (Object declaration : declarations) {
                    if (dependencies.size() >= MAX_DEPENDENCIES
                            || !(declaration instanceof UnmodifiableConfig dep))
                        throw new IOException("Staged dependency declarations are incomplete");
                    dependencies.add(parseDependency(ownerId, dep));
                }
            }
        }
        return new ClientCleanupGraph.PhysicalJar(filename, List.copyOf(new TreeSet<>(ids)),
                dependencies, true);
    }

    private static ClientCleanupGraph.Dependency parseDependency(String ownerId,
            UnmodifiableConfig dep) throws IOException {
        if (!(dep.get("modId") instanceof String targetId)
                || !validFmlModId(targetId))
            throw new IOException("Staged dependency target is unresolved");
        String typeText = stringOrDefault(dep.get("type"), "required");
        String sideText = stringOrDefault(dep.get("side"), "BOTH");
        String rangeText = stringOrDefault(dep.get("versionRange"), "");
        if (typeText == null || sideText == null || rangeText == null
                || rangeText.length() > 512 || rangeText.indexOf('$') >= 0)
            throw new IOException("Staged dependency properties are unresolved");
        try {
            ClientCleanupGraph.DependencyType type = ClientCleanupGraph.DependencyType.valueOf(
                    typeText.toUpperCase(Locale.ROOT));
            ClientCleanupGraph.DependencySide side = ClientCleanupGraph.DependencySide.valueOf(
                    sideText.toUpperCase(Locale.ROOT));
            if (type == ClientCleanupGraph.DependencyType.UNKNOWN
                    || side == ClientCleanupGraph.DependencySide.UNKNOWN)
                throw new IllegalArgumentException("Unknown FML dependency declaration");
            if (!rangeText.isBlank()) VersionRange.createFromVersionSpec(rangeText);
            return new ClientCleanupGraph.Dependency(ownerId, targetId, type, side, rangeText);
        } catch (Exception malformed) {
            throw new IOException("Staged dependency type, side or range is unresolved", malformed);
        }
    }

    private static String stringOrDefault(Object value, String fallback) {
        return value == null ? fallback : value instanceof String string ? string : null;
    }

    private static boolean validFmlModId(String id) {
        return id != null && FML_MOD_ID.matcher(id).matches();
    }

    private static boolean safeEntryName(String name) {
        if (name == null || name.isBlank() || name.length() > 512 || name.startsWith("/")
                || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0) return false;
        for (String component : name.split("/"))
            if (component.isEmpty() || component.equals(".") || component.equals("..")) return false;
        return true;
    }
}
