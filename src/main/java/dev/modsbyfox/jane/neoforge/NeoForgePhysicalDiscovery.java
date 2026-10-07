package dev.modsbyfox.jane.neoforge;

import dev.modsbyfox.jane.core.PathSafety;
import java.io.IOException;
import java.lang.annotation.ElementType;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
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

    private NeoForgePhysicalDiscovery() {}

    /**
     * A single on-disk JAR, which may contain more than one FML mod ID. The
     * lexicographically first ID supplies the stable manifest identity and its
     * display name and version. Entrypoint evidence concerns the whole JAR.
     */
    public record PhysicalJar(Path jar, List<String> modIds, String canonicalId,
                              String displayName, String version,
                              ClientRequirementClassifier.FmlEvidence fmlEvidence) {
        public PhysicalJar {
            Objects.requireNonNull(jar, "jar");
            modIds = List.copyOf(Objects.requireNonNull(modIds, "modIds"));
            Objects.requireNonNull(canonicalId, "canonicalId");
            Objects.requireNonNull(displayName, "displayName");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(fmlEvidence, "fmlEvidence");
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
            boolean nested = file.getDiscoveryAttributes() != null
                    && file.getDiscoveryAttributes().parent() != null;
            List<LoadedMod> mods = new ArrayList<>();
            for (IModInfo mod : info.getMods()) {
                mods.add(new LoadedMod(mod.getModId(), mod.getDisplayName(),
                        mod.getVersion().toString()));
            }
            boolean scanComplete = info.getMods().stream().allMatch(mod ->
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
            files.add(new LoadedFile(file.getFilePath(), nested, mods, entrypoints, scanComplete));
        }
        return fromLoadedFiles(gameDir, files);
    }

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
            if (file.nested() || file.mods().isEmpty() || file.path() == null) {
                continue;
            }
            Path origin = file.path().toAbsolutePath().normalize();
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

    record LoadedMod(String modId, String displayName, String version) {}

    record Entrypoint(String modId, ClientRequirementClassifier.EntrypointSide side) {}

    record LoadedFile(Path path, boolean nested, List<LoadedMod> mods,
                      List<Entrypoint> entrypoints, boolean scanComplete) {
        LoadedFile {
            mods = List.copyOf(mods);
            entrypoints = List.copyOf(entrypoints);
        }
    }

    private static final class JarBuilder {
        private final Path jar;
        private final Map<String, LoadedMod> mods = new TreeMap<>();
        private final List<Entrypoint> entrypoints = new ArrayList<>();
        private boolean scanComplete = true;

        private JarBuilder(Path jar) {
            this.jar = jar;
        }

        private void add(LoadedFile file) throws IOException {
            scanComplete &= file.scanComplete();
            for (LoadedMod mod : file.mods()) {
                if (mod.modId() == null || mod.modId().isBlank()) {
                    throw new IOException("FML returned a mod without an ID");
                }
                LoadedMod previous = mods.putIfAbsent(mod.modId(), mod);
                if (previous != null && (!Objects.equals(previous.displayName(), mod.displayName())
                        || !Objects.equals(previous.version(), mod.version()))) {
                    throw new IOException("FML returned conflicting metadata for one physical JAR");
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
                    new ClientRequirementClassifier.FmlEvidence(scanComplete, sides));
        }
    }
}
