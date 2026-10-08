package dev.modsbyfox.jane.neoforge;

import dev.modsbyfox.jane.core.PathSafety;
import java.io.IOException;
import java.lang.annotation.ElementType;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.modscan.ModAnnotation;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforgespi.language.ModFileScanData;
import net.neoforged.neoforgespi.locating.IModFile;
import org.objectweb.asm.Type;

/** Maps FML's resolved mod files to direct, physical JARs in this game instance. */
public final class NeoForgePhysicalDiscovery {
    private static final String JANE_ID = "jane";
    private static final int MAX_DEPENDENCY_NOTES = 512;

    private NeoForgePhysicalDiscovery() {}

    /**
     * A single on-disk JAR, which may contain more than one FML mod ID. The
     * lexicographically first ID supplies the stable manifest identity and its
     * display name and version. Nested FML mods are attributed to their outer
     * physical JAR; they are never separate sync units.
     */
    public record PhysicalJar(Path jar, List<String> modIds, String canonicalId,
                              String displayName, String version,
                              ClientRequirementClassifier.FmlEvidence fmlEvidence,
                              List<String> dependencyDescriptions) {
        public PhysicalJar {
            Objects.requireNonNull(jar, "jar");
            modIds = List.copyOf(Objects.requireNonNull(modIds, "modIds"));
            Objects.requireNonNull(canonicalId, "canonicalId");
            Objects.requireNonNull(displayName, "displayName");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(fmlEvidence, "fmlEvidence");
            dependencyDescriptions = List.copyOf(Objects.requireNonNull(
                    dependencyDescriptions, "dependencyDescriptions"));
        }

        public PhysicalJar(Path jar, List<String> modIds, String canonicalId,
                           String displayName, String version,
                           ClientRequirementClassifier.FmlEvidence fmlEvidence) {
            this(jar, modIds, canonicalId, displayName, version, fmlEvidence, List.of());
        }
    }

    /** Must be called after FML has completed mod discovery. */
    public static List<PhysicalJar> discover(Path gameDir) throws IOException {
        ModList modList = ModList.get();
        if (modList == null) {
            throw new IllegalStateException("FML mod discovery has not completed");
        }
        List<LoadedFile> files = new ArrayList<>();
        for (IModFileInfo info : modList.getModFiles()) {
            IModFile file = info.getFile();
            if (file == null) {
                throw new IOException("FML returned a mod file without an origin");
            }
            PhysicalOrigin origin = physicalOrigin(file);
            boolean scanComplete = true;
            List<LoadedMod> mods = new ArrayList<>();
            for (IModInfo mod : info.getMods()) {
                List<String> dependencies = new ArrayList<>();
                if (mod.getDependencies() == null) {
                    scanComplete = false;
                } else {
                    for (IModInfo.ModVersion dependency : mod.getDependencies()) {
                        if (dependency == null || dependency.getModId() == null
                                || dependency.getType() == null || dependency.getSide() == null
                                || dependencies.size() >= MAX_DEPENDENCY_NOTES) {
                            scanComplete = false;
                            continue;
                        }
                        String note = "owner=" + mod.getModId() + " target=" + dependency.getModId()
                                + " type=" + dependency.getType() + " targetSide=" + dependency.getSide();
                        if (note.length() > 512) {
                            scanComplete = false;
                            continue;
                        }
                        dependencies.add(note);
                    }
                }
                mods.add(new LoadedMod(mod.getModId(), mod.getDisplayName(),
                        mod.getVersion().toString(), dependencies));
            }
            scanComplete &= info.getMods().stream().allMatch(mod ->
                    mod.getLoader() != null && "javafml".equals(mod.getLoader().name()));
            List<Entrypoint> entrypoints = new ArrayList<>();
            ModFileScanData scan = file.getScanResult();
            if (scan == null) {
                scanComplete = false;
            } else {
                for (ModFileScanData.AnnotationData annotation :
                        scan.getAnnotatedBy(Mod.class, ElementType.TYPE).toList()) {
                    Map<String, Object> values = annotation.annotationData();
                    Object modId = values.get("value");
                    ClientRequirementClassifier.EntrypointSide side = entrypointSide(values);
                    if (!(modId instanceof String id) || side == null) {
                        scanComplete = false;
                    } else {
                        entrypoints.add(new Entrypoint(id, side));
                    }
                }
            }
            files.add(new LoadedFile(file.getFilePath(), origin.nested(), origin.root(),
                    mods, entrypoints, scanComplete));
        }
        return fromLoadedFiles(gameDir, files);
    }

    /** Follow FML's parent relationship, including a top-level library with no own mod ID. */
    private static PhysicalOrigin physicalOrigin(IModFile file) throws IOException {
        Set<IModFile> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        IModFile current = file;
        boolean nested = false;
        for (int depth = 0; depth < 64; depth++) {
            if (!visited.add(current)) throw new IOException("Cyclic FML Jar-in-Jar parent relationship");
            IModFile parent = current.getDiscoveryAttributes() == null
                    ? null : current.getDiscoveryAttributes().parent();
            if (parent == null) {
                return new PhysicalOrigin(current.getFilePath(), nested);
            }
            nested = true;
            current = parent;
        }
        throw new IOException("FML Jar-in-Jar parent relationship exceeds depth limit");
    }

    private record PhysicalOrigin(Path root, boolean nested) {}

    /**
     * FML represents an explicit dist array as a list of EnumHolder values.
     * An absent array is @Mod's default, which loads on both physical sides.
     */
    static ClientRequirementClassifier.EntrypointSide entrypointSide(Map<String, Object> values) {
        if (!values.containsKey("dist")) {
            return ClientRequirementClassifier.EntrypointSide.DEFAULT;
        }
        if (!(values.get("dist") instanceof List<?> rawSides) || rawSides.isEmpty()) {
            return null;
        }
        boolean client = false;
        boolean server = false;
        String distDescriptor = Type.getDescriptor(Dist.class);
        for (Object rawSide : rawSides) {
            if (!(rawSide instanceof ModAnnotation.EnumHolder holder)
                    || !distDescriptor.equals(holder.desc())) {
                return null;
            }
            switch (holder.value()) {
                case "CLIENT" -> client = true;
                case "DEDICATED_SERVER" -> server = true;
                default -> { return null; }
            }
        }
        if (client && server) return ClientRequirementClassifier.EntrypointSide.BOTH;
        return client ? ClientRequirementClassifier.EntrypointSide.CLIENT
                : ClientRequirementClassifier.EntrypointSide.DEDICATED_SERVER;
    }

    /** Package-private seam for testing path safety and physical-file aggregation. */
    static List<PhysicalJar> fromLoadedFiles(Path gameDir, List<LoadedFile> files) throws IOException {
        Objects.requireNonNull(gameDir, "gameDir");
        Objects.requireNonNull(files, "files");
        Path lexicalMods = gameDir.toAbsolutePath().normalize().resolve("mods");
        Map<Path, JarBuilder> byJar = new LinkedHashMap<>();
        for (LoadedFile file : files) {
            if (file.mods().isEmpty()) {
                continue;
            }
            Path physicalRoot = file.nested() ? file.physicalRoot() : file.path();
            if (physicalRoot == null) {
                if (file.nested()) throw new IOException("Nested FML mod has no top-level physical origin");
                continue;
            }
            Path origin = physicalRoot.toAbsolutePath().normalize();
            // FML also lists built-in, development and jar-in-jar mod files.
            // Reject those before asking PathSafety to resolve any filesystem path.
            if (!lexicalMods.equals(origin.getParent())) {
                continue;
            }
            try {
                PathSafety.safeExistingJarName(origin.getFileName().toString());
            } catch (IllegalArgumentException invalidName) {
                throw new IOException("FML mod JAR has an unsafe file name", invalidName);
            }
            Path verified = PathSafety.existingJarInMods(gameDir, origin);
            JarBuilder builder = byJar.computeIfAbsent(verified, JarBuilder::new);
            builder.add(file);
        }
        List<PhysicalJar> jars = new ArrayList<>();
        for (JarBuilder builder : byJar.values()) {
            PhysicalJar jar = builder.build();
            if (jar != null) jars.add(jar);
        }
        jars.sort(Comparator.comparing(PhysicalJar::canonicalId)
                .thenComparing(jar -> jar.jar().getFileName().toString()));
        return List.copyOf(jars);
    }

    record LoadedMod(String modId, String displayName, String version,
                     List<String> dependencyDescriptions) {
        LoadedMod {
            dependencyDescriptions = List.copyOf(dependencyDescriptions);
        }

        LoadedMod(String modId, String displayName, String version) {
            this(modId, displayName, version, List.of());
        }
    }

    record Entrypoint(String modId, ClientRequirementClassifier.EntrypointSide side) {}

    record LoadedFile(Path path, boolean nested, Path physicalRoot, List<LoadedMod> mods,
                      List<Entrypoint> entrypoints, boolean scanComplete) {
        LoadedFile {
            mods = List.copyOf(mods);
            entrypoints = List.copyOf(entrypoints);
        }

        LoadedFile(Path path, boolean nested, List<LoadedMod> mods,
                   List<Entrypoint> entrypoints, boolean scanComplete) {
            this(path, nested, nested ? null : path, mods, entrypoints, scanComplete);
        }
    }

    private static final class JarBuilder {
        private final Path jar;
        private final Map<String, LoadedMod> mods = new TreeMap<>();
        private final List<Entrypoint> entrypoints = new ArrayList<>();
        private final List<String> dependencyDescriptions = new ArrayList<>();
        private boolean scanComplete = true;

        private JarBuilder(Path jar) {
            this.jar = jar;
        }

        private void add(LoadedFile file) throws IOException {
            scanComplete &= file.scanComplete();
            // The outer ZIP scanner cannot inspect inner mod bytecode, so FML
            // entrypoints alone must not automatically exclude this container.
            if (file.nested()) scanComplete = false;
            for (LoadedMod mod : file.mods()) {
                if (mod.modId() == null || mod.modId().isBlank()) {
                    throw new IOException("FML returned a mod without an ID");
                }
                LoadedMod previous = mods.putIfAbsent(mod.modId(), mod);
                if (previous != null && (!Objects.equals(previous.displayName(), mod.displayName())
                        || !Objects.equals(previous.version(), mod.version())
                        || !Objects.equals(previous.dependencyDescriptions(), mod.dependencyDescriptions()))) {
                    throw new IOException("FML returned conflicting metadata for one physical JAR");
                }
                if (previous == null) {
                    for (String description : mod.dependencyDescriptions()) {
                        if (dependencyDescriptions.size() >= MAX_DEPENDENCY_NOTES) {
                            scanComplete = false;
                            break;
                        }
                        dependencyDescriptions.add(description);
                    }
                }
            }
            entrypoints.addAll(file.entrypoints());
        }

        private PhysicalJar build() throws IOException {
            if (mods.containsKey(JANE_ID) && mods.size() > 1) {
                throw new IOException("Jane is bundled with other mod IDs in one physical JAR");
            }
            if (mods.containsKey(JANE_ID) || mods.isEmpty()) {
                return null;
            }
            Set<String> idsWithEntrypoints = new TreeSet<>();
            List<ClientRequirementClassifier.EntrypointSide> sides = new ArrayList<>();
            for (Entrypoint entrypoint : entrypoints) {
                if (!mods.containsKey(entrypoint.modId()) || entrypoint.side() == null) {
                    scanComplete = false;
                    continue;
                }
                idsWithEntrypoints.add(entrypoint.modId());
                sides.add(entrypoint.side());
            }
            // A TOML entry may have no @Mod class (notably lowcodefml). Such a
            // file cannot be proven dedicated-server-only from @Mod annotations.
            scanComplete &= idsWithEntrypoints.containsAll(mods.keySet());
            Map.Entry<String, LoadedMod> canonical = mods.entrySet().iterator().next();
            LoadedMod metadata = canonical.getValue();
            if (metadata.displayName() == null || metadata.version() == null) {
                throw new IOException("FML returned incomplete metadata for one physical JAR");
            }
            return new PhysicalJar(jar, List.copyOf(mods.keySet()), canonical.getKey(),
                    metadata.displayName(), metadata.version(),
                    new ClientRequirementClassifier.FmlEvidence(scanComplete, sides),
                    dependencyDescriptions);
        }
    }
}
