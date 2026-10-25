package dev.modsbyfox.jane.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** Immutable, local-only dependency snapshot for a proposed set of removed physical JARs. */
public final class ClientCleanupGraph {
    // FML IDs include Jane and may contain dots; RequiredManifest's ID rule is narrower.
    private static final Pattern FML_ID = Pattern.compile("[a-z][a-z0-9_.-]{0,127}");
    public enum DependencyType { REQUIRED, OPTIONAL, INCOMPATIBLE, DISCOURAGED, UNKNOWN }
    public enum DependencySide { CLIENT, SERVER, BOTH, UNKNOWN }
    public enum Reason {
        UNKNOWN_METADATA, REQUIRED_BY_OTHER_MOD, UNKNOWN_DEPENDENCY,
        DUPLICATE_MOD_ID, UNKNOWN_SELECTION
    }

    public record Dependency(String ownerModId, String targetModId, DependencyType type,
                             DependencySide side, String versionRange) {
        public Dependency {
            if (!validModId(ownerModId) || !validModId(targetModId)
                    || type == null || side == null || versionRange == null) {
                throw new IllegalArgumentException("Invalid FML dependency");
            }
        }
    }

    /** Every ID belongs to this one top-level physical file, including bundled IDs. */
    public record PhysicalJar(String filename, List<String> modIds,
                              List<Dependency> dependencies, boolean metadataComplete,
                              String observedSha512) {
        public PhysicalJar {
            if (filename == null || filename.isBlank() || modIds == null || modIds.isEmpty()
                    || dependencies == null) throw new IllegalArgumentException("Invalid physical JAR");
            modIds = List.copyOf(modIds);
            dependencies = List.copyOf(dependencies);
            Set<String> ids = Set.copyOf(modIds);
            if (modIds.stream().anyMatch(id -> !validModId(id))
                    || dependencies.stream().anyMatch(dep -> !ids.contains(dep.ownerModId()))) {
                throw new IllegalArgumentException("Physical JAR has unmapped FML metadata");
            }
            if (observedSha512 != null && !observedSha512.matches("[0-9a-f]{128}"))
                throw new IllegalArgumentException("Invalid observed physical JAR hash");
        }

        /** Fixtures and unavailable legacy snapshots have no disk hash proof. */
        public PhysicalJar(String filename, List<String> modIds,
                           List<Dependency> dependencies, boolean metadataComplete) {
            this(filename, modIds, dependencies, metadataComplete, null);
        }
    }

    /** Mods from FML which do not belong to a manageable top-level mods-directory JAR. */
    public record RetainedMod(String modId, List<Dependency> dependencies,
                              boolean metadataComplete) {
        public RetainedMod {
            if (!validModId(modId) || dependencies == null)
                throw new IllegalArgumentException("Invalid retained FML mod");
            dependencies = List.copyOf(dependencies);
            if (dependencies.stream().anyMatch(dep -> !modId.equals(dep.ownerModId())))
                throw new IllegalArgumentException("Retained dependency has another owner");
        }
    }

    public record Block(Reason reason, String ownerModId, String targetModId) {
        public Block {
            Objects.requireNonNull(reason, "reason");
        }
    }

    public record Decision(boolean eligible, Block block, List<Dependency> optionalImpacts) {
        public Decision {
            optionalImpacts = List.copyOf(optionalImpacts);
            if (eligible != (block == null)) throw new IllegalArgumentException("Inconsistent cleanup decision");
        }
    }

    public record Evaluation(Set<String> selectedFilenames, Map<String, Decision> byFilename,
                             boolean safe) {
        public Evaluation {
            selectedFilenames = Set.copyOf(selectedFilenames);
            byFilename = Collections.unmodifiableMap(new LinkedHashMap<>(byFilename));
        }

        public Decision decision(String filename) {
            return byFilename.get(filename);
        }
    }

    private final List<PhysicalJar> jars;
    private final List<RetainedMod> externalMods;
    private final boolean complete;
    private final String fingerprint;

    public ClientCleanupGraph(List<PhysicalJar> jars, List<RetainedMod> externalMods,
                              boolean complete) {
        this.jars = List.copyOf(Objects.requireNonNull(jars, "jars"));
        this.externalMods = List.copyOf(Objects.requireNonNull(externalMods, "externalMods"));
        this.complete = complete;
        this.fingerprint = calculateFingerprint();
    }

    /** Legacy or unavailable discovery cannot authorize cleanup. */
    public static ClientCleanupGraph unavailable() {
        return new ClientCleanupGraph(List.of(), List.of(), false);
    }

    public List<PhysicalJar> jars() { return jars; }
    public List<RetainedMod> externalMods() { return externalMods; }
    public boolean complete() { return complete; }
    public String fingerprint() { return fingerprint; }

    /**
     * Evaluate the final selected physical files. An owner selected for removal no
     * longer blocks removal of its required target; an owner left behind does.
     * This performs no disk I/O and is safe to repeat after a UI toggle.
     */
    public Evaluation evaluate(Set<String> selectedFilenames) {
        Objects.requireNonNull(selectedFilenames, "selectedFilenames");
        Set<String> selected = Set.copyOf(selectedFilenames);
        Map<String, PhysicalJar> byName = new TreeMap<>();
        Map<String, String> ownerFileById = new TreeMap<>();
        Set<String> duplicateIds = new TreeSet<>();
        Set<String> foldedNames = new TreeSet<>();
        boolean metadataKnown = complete;
        for (PhysicalJar jar : jars) {
            if (byName.putIfAbsent(jar.filename(), jar) != null) metadataKnown = false;
            if (!foldedNames.add(jar.filename().toLowerCase(Locale.ROOT))) metadataKnown = false;
            for (String id : jar.modIds()) {
                if (ownerFileById.putIfAbsent(id, jar.filename()) != null) duplicateIds.add(id);
            }
            if (!jar.metadataComplete()) metadataKnown = false;
        }
        for (RetainedMod mod : externalMods) {
            if (ownerFileById.putIfAbsent(mod.modId(), "") != null) duplicateIds.add(mod.modId());
            if (!mod.metadataComplete()) metadataKnown = false;
        }

        Map<String, Block> blocked = new TreeMap<>();
        Map<String, List<Dependency>> optional = new TreeMap<>();
        for (String filename : selected) {
            if (!byName.containsKey(filename))
                blocked.put(filename, new Block(Reason.UNKNOWN_SELECTION, null, null));
            else if (byName.get(filename).modIds().contains("jane"))
                blocked.put(filename, new Block(Reason.UNKNOWN_SELECTION, null, "jane"));
            else if (!metadataKnown)
                blocked.put(filename, new Block(Reason.UNKNOWN_METADATA, null, null));
            else if (!duplicateIds.isEmpty())
                blocked.put(filename, new Block(Reason.DUPLICATE_MOD_ID, null,
                        duplicateIds.iterator().next()));
        }
        for (PhysicalJar jar : jars) {
            if (!selected.contains(jar.filename()))
                evaluateDependencies(jar.dependencies(), ownerFileById, selected, blocked, optional);
        }
        for (RetainedMod mod : externalMods)
            evaluateDependencies(mod.dependencies(), ownerFileById, selected, blocked, optional);

        Map<String, Decision> decisions = new LinkedHashMap<>();
        for (String filename : byName.keySet()) {
            Block block = blocked.get(filename);
            decisions.put(filename, new Decision(block == null, block,
                    optional.getOrDefault(filename, List.of())));
        }
        for (String filename : new TreeSet<>(selected)) {
            if (!decisions.containsKey(filename))
                decisions.put(filename, new Decision(false, blocked.get(filename), List.of()));
        }
        boolean safe = selected.stream().allMatch(filename -> decisions.get(filename).eligible());
        return new Evaluation(selected, decisions, safe);
    }

    /** Select all safe candidates, then remove blocked items until the graph is stable. */
    public Set<String> safeDefaultSelection(Set<String> candidateFilenames) {
        Objects.requireNonNull(candidateFilenames, "candidateFilenames");
        Set<String> selected = new TreeSet<>(candidateFilenames);
        while (!selected.isEmpty()) {
            Evaluation evaluation = evaluate(selected);
            Set<String> blocked = new TreeSet<>();
            for (String filename : selected) {
                if (!evaluation.decision(filename).eligible()) blocked.add(filename);
            }
            if (blocked.isEmpty()) break;
            selected.removeAll(blocked);
        }
        return Set.copyOf(selected);
    }

    private static void evaluateDependencies(Collection<Dependency> dependencies,
            Map<String, String> ownerFileById, Set<String> selected,
            Map<String, Block> blocked, Map<String, List<Dependency>> optional) {
        for (Dependency dependency : dependencies) {
            String targetFile = ownerFileById.get(dependency.targetModId());
            if (targetFile == null || targetFile.isEmpty() || !selected.contains(targetFile)) continue;
            if (dependency.side() == DependencySide.SERVER) continue;
            if (dependency.type() == DependencyType.REQUIRED
                    && (dependency.side() == DependencySide.CLIENT
                            || dependency.side() == DependencySide.BOTH)) {
                blocked.putIfAbsent(targetFile, new Block(Reason.REQUIRED_BY_OTHER_MOD,
                        dependency.ownerModId(), dependency.targetModId()));
            } else if (dependency.type() == DependencyType.OPTIONAL
                    && dependency.side() != DependencySide.UNKNOWN) {
                optional.computeIfAbsent(targetFile, ignored -> new ArrayList<>()).add(dependency);
            } else if (dependency.type() == DependencyType.UNKNOWN
                    || dependency.side() == DependencySide.UNKNOWN) {
                blocked.putIfAbsent(targetFile, new Block(Reason.UNKNOWN_DEPENDENCY,
                        dependency.ownerModId(), dependency.targetModId()));
            }
            // INCOMPATIBLE and DISCOURAGED do not require the target to remain.
        }
    }

    private String calculateFingerprint() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            append(digest, "jane-client-cleanup-graph-v2");
            append(digest, Boolean.toString(complete));
            for (PhysicalJar jar : jars.stream().sorted(Comparator.comparing(PhysicalJar::filename)).toList()) {
                append(digest, "jar");
                append(digest, jar.filename());
                append(digest, Boolean.toString(jar.metadataComplete()));
                append(digest, jar.observedSha512() == null ? "<unobserved>" : jar.observedSha512());
                appendSorted(digest, jar.modIds());
                appendDependencies(digest, jar.dependencies());
            }
            for (RetainedMod mod : externalMods.stream().sorted(Comparator.comparing(RetainedMod::modId)).toList()) {
                append(digest, "retained");
                append(digest, mod.modId());
                append(digest, Boolean.toString(mod.metadataComplete()));
                appendDependencies(digest, mod.dependencies());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void appendDependencies(MessageDigest digest, List<Dependency> dependencies) {
        List<Dependency> sorted = dependencies.stream().sorted(Comparator
                .comparing(Dependency::ownerModId).thenComparing(Dependency::targetModId)
                .thenComparing(dep -> dep.type().name()).thenComparing(dep -> dep.side().name())
                .thenComparing(Dependency::versionRange)).toList();
        append(digest, Integer.toString(sorted.size()));
        for (Dependency dependency : sorted) {
            append(digest, dependency.ownerModId());
            append(digest, dependency.targetModId());
            append(digest, dependency.type().name());
            append(digest, dependency.side().name());
            append(digest, dependency.versionRange());
        }
    }

    private static void appendSorted(MessageDigest digest, List<String> values) {
        append(digest, Integer.toString(values.size()));
        for (String value : new TreeSet<>(values)) append(digest, value);
    }

    private static void append(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    public static boolean validModId(String value) {
        return value != null && FML_ID.matcher(value).matches();
    }
}
