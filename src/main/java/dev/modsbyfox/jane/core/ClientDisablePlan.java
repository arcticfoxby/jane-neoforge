package dev.modsbyfox.jane.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** A local-only, exact-file disable operation, separate from server-required updates. */
public record ClientDisablePlan(String serverId, String timestamp, List<Entry> entries,
                                List<DependencyGuard> dependencyGuards, String stagedGraphFingerprint) {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    public record Entry(String modId, String displayName, String version, String filename, String sha512,
                        ClientCompatibilityReport.Kind kind, List<String> containedModIds) {
        public Entry(String modId, String displayName, String version, String filename, String sha512) {
            this(modId, displayName, version, filename, sha512,
                    ClientCompatibilityReport.Kind.EXPLICIT_CLIENT, List.of(modId));
        }

        public Entry {
            if (!ManifestEntry.validId(modId) || displayName == null || displayName.isBlank()
                    || version == null || version.isBlank() || sha512 == null
                    || !sha512.matches("[0-9a-f]{128}")
                    || (kind != ClientCompatibilityReport.Kind.EXPLICIT_CLIENT
                    && kind != ClientCompatibilityReport.Kind.EXPLICIT_SERVER))
                throw new IllegalArgumentException("Invalid disable entry");
            PathSafety.safeExistingJarName(filename);
            containedModIds = List.copyOf(containedModIds);
            if (containedModIds.isEmpty() || !containedModIds.contains(modId)
                    || containedModIds.stream().anyMatch(id -> !ClientCleanupGraph.validModId(id))
                    || containedModIds.stream().distinct().count() != containedModIds.size())
                throw new IllegalArgumentException("Invalid contained mod IDs");
        }
    }

    public record DependencyGuard(String filename, String sha512) {
        public DependencyGuard {
            PathSafety.safeExistingJarName(filename);
            if (sha512 == null || !sha512.matches("[0-9a-f]{128}"))
                throw new IllegalArgumentException("Invalid dependency guard");
        }
    }

    public ClientDisablePlan(String serverId, String timestamp, List<Entry> entries) {
        this(serverId, timestamp, entries, List.of(), null);
    }

    public ClientDisablePlan(String serverId, String timestamp, List<Entry> entries,
                             List<DependencyGuard> dependencyGuards) {
        this(serverId, timestamp, entries, dependencyGuards, null);
    }

    public ClientDisablePlan {
        if (serverId == null || !serverId.matches("[0-9a-f]{64}") || timestamp == null
                || !timestamp.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}_[0-9]{2}-[0-9]{2}-[0-9]{2}"))
            throw new IllegalArgumentException("Invalid disable identity");
        entries = List.copyOf(entries);
        dependencyGuards = List.copyOf(dependencyGuards);
        if (stagedGraphFingerprint != null && !stagedGraphFingerprint.matches("[0-9a-f]{128}"))
            throw new IllegalArgumentException("Invalid staged dependency fingerprint");
        if (entries.isEmpty() || entries.size() > RequiredManifest.MAX_ENTRIES)
            throw new IllegalArgumentException("Invalid disable entry count");
        Set<String> names = new HashSet<>();
        for (Entry entry : entries)
            if (!names.add(entry.filename().toLowerCase(Locale.ROOT)))
                throw new IllegalArgumentException("Duplicate disable filename");
        Set<String> guardNames = new HashSet<>();
        for (DependencyGuard guard : dependencyGuards)
            if (!guardNames.add(guard.filename().toLowerCase(Locale.ROOT)))
                throw new IllegalArgumentException("Duplicate dependency guard filename");
    }

    public static ClientDisablePlan prepare(Path gameDir, String serverId, ClientCompatibilityReport report,
                                            List<ClientCompatibilityReport.ExtraMod> selected) throws IOException {
        if (report == null || selected == null || selected.isEmpty()) throw new IOException("No client mods selected");
        List<Entry> entries = new ArrayList<>();
        for (ClientCompatibilityReport.ExtraMod mod : selected) {
            if (mod.kind() != ClientCompatibilityReport.Kind.EXPLICIT_CLIENT
                    || !report.explicitClientMods().contains(mod)) throw new IOException("Selection is not an explicit client mod");
            String filename = PathSafety.safeExistingJarName(mod.filename());
            Path jar = PathSafety.existingJarInMods(gameDir, mod.jar());
            if (!jar.getFileName().toString().equals(filename) || !Hashing.sha512(jar).equals(mod.sha512()))
                throw new IOException("Selected client JAR changed");
            entries.add(new Entry(mod.modId(), mod.displayName(), mod.version(), filename, mod.sha512(),
                    mod.kind(), mod.containedModIds()));
        }
        Path parent = PathSafety.janeDirectoryPath(gameDir, "disabled", serverId);
        Path backupParent = PathSafety.janeDirectoryPath(gameDir, "backups", serverId);
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < 60; i++) {
            String timestamp = now.plusSeconds(i).format(STAMP);
            if (!Files.exists(parent.resolve(timestamp), LinkOption.NOFOLLOW_LINKS)
                    && !Files.exists(backupParent.resolve(timestamp), LinkOption.NOFOLLOW_LINKS))
                return new ClientDisablePlan(serverId, timestamp, entries);
        }
        throw new IOException("No free disable timestamp");
    }

    /** Strict local proof for dedicated-server-entry cleanup; never trusts kind alone. */
    public static ClientDisablePlan prepare(Path gameDir, String serverId, RequiredManifest manifest,
                                            ClientCompatibilityReport report,
                                            List<ClientCompatibilityReport.ExtraMod> selected) throws IOException {
        return prepareForGraph(gameDir, serverId, manifest, report, selected,
                report == null ? null : report.cleanupGraph(), null);
    }

    /** Validate a merged post-install graph before a combined offline transaction. */
    public static ClientDisablePlan prepare(Path gameDir, String serverId, RequiredManifest manifest,
                                            ClientCompatibilityReport report,
                                            List<ClientCompatibilityReport.ExtraMod> selected,
                                            UpdatePlan installPlan,
                                            ClientCleanupGraph futureGraph) throws IOException {
        if (installPlan == null || installPlan.operations().isEmpty()
                || !installPlan.cleanupEntries().isEmpty()
                || !installPlan.serverId().equals(serverId) || futureGraph == null
                || !futureGraph.complete())
            throw new IOException("Staged dependency proof is unavailable");
        if (report == null || !report.cleanupGraph().complete()
                || !futureGraph.externalMods().equals(report.cleanupGraph().externalMods()))
            throw new IOException("Future dependency graph differs from live FML evidence");
        Set<String> replacedFiles = new HashSet<>();
        Set<String> expectedFutureFiles = new HashSet<>();
        for (UpdatePlan.Operation op : installPlan.operations()) {
            expectedFutureFiles.add(op.newFile());
            if (op.kind() == UpdatePlan.Kind.REPLACE) replacedFiles.add(op.oldFile());
        }
        for (ClientCleanupGraph.PhysicalJar jar : report.cleanupGraph().jars()) {
            if (replacedFiles.contains(jar.filename())) continue;
            expectedFutureFiles.add(jar.filename());
            if (!futureGraph.jars().contains(jar))
                throw new IOException("Future dependency graph changed a retained JAR");
        }
        if (!futureGraph.jars().stream().map(ClientCleanupGraph.PhysicalJar::filename)
                .collect(java.util.stream.Collectors.toSet()).equals(expectedFutureFiles))
            throw new IOException("Future dependency graph has an unexpected physical JAR");
        Path staging = PathSafety.janeDirectoryPath(gameDir, "staging", installPlan.syncId());
        for (UpdatePlan.Operation op : installPlan.operations()) {
            Path source = staging.resolve(op.newFile());
            PathSafety.rejectReparsePoint(source);
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(source) != op.size() || !Hashing.sha512(source).equals(op.newHash()))
                throw new IOException("Staged JAR changed before cleanup planning");
            boolean represented = futureGraph.jars().stream().anyMatch(jar -> jar.filename().equals(op.newFile())
                    && jar.modIds().contains(op.modId()) && jar.metadataComplete());
            if (!represented) throw new IOException("Staged JAR is missing from future dependency graph");
        }
        return prepareForGraph(gameDir, serverId, manifest, report, selected,
                futureGraph, futureGraph.fingerprint());
    }

    private static ClientDisablePlan prepareForGraph(Path gameDir, String serverId, RequiredManifest manifest,
                                                     ClientCompatibilityReport report,
                                                     List<ClientCompatibilityReport.ExtraMod> selected,
                                                     ClientCleanupGraph evaluatedGraph,
                                                     String stagedFingerprint) throws IOException {
        if (manifest == null || report == null || selected == null || selected.isEmpty()
                || (selected.stream().anyMatch(mod -> mod.kind() == ClientCompatibilityReport.Kind.EXPLICIT_SERVER)
                && (!report.cleanupGraph().complete() || evaluatedGraph == null || !evaluatedGraph.complete())))
            throw new IOException("Client cleanup evidence is incomplete");
        if (selected.stream().noneMatch(mod -> mod.kind() == ClientCompatibilityReport.Kind.EXPLICIT_SERVER)
                && stagedFingerprint == null)
            return prepare(gameDir, serverId, report, selected);
        Set<String> requiredIds = new HashSet<>();
        Set<String> requiredHashes = new HashSet<>();
        for (ManifestEntry entry : manifest.entries()) {
            requiredIds.add(entry.modId());
            requiredHashes.add(entry.sha512());
        }
        Set<String> filenames = new HashSet<>();
        Set<String> foldedNames = new HashSet<>();
        List<Entry> entries = new ArrayList<>();
        for (ClientCompatibilityReport.ExtraMod mod : selected) {
            boolean serverEntry = mod.kind() == ClientCompatibilityReport.Kind.EXPLICIT_SERVER;
            boolean clientEntry = mod.kind() == ClientCompatibilityReport.Kind.EXPLICIT_CLIENT;
            if ((!serverEntry && !clientEntry)
                    || !(serverEntry ? report.otherExtraMods() : report.explicitClientMods()).contains(mod)
                    || !mod.verified()
                    || mod.containedModIds().contains("jane")
                    || mod.containedModIds().stream().anyMatch(requiredIds::contains)
                    || requiredHashes.contains(mod.sha512()))
                throw new IOException("Selection is not a verified extra JAR");
            String filename = PathSafety.safeExistingJarName(mod.filename());
            if (!filenames.add(filename) || !foldedNames.add(filename.toLowerCase(Locale.ROOT)))
                throw new IOException("Duplicate client cleanup filename");
            ClientCleanupGraph.PhysicalJar physical = report.cleanupGraph().jars().stream()
                    .filter(jar -> jar.filename().equals(filename)).findFirst()
                    .orElseThrow(() -> new IOException("Client cleanup physical JAR is unknown"));
            if (!physical.metadataComplete()
                    || !Set.copyOf(physical.modIds()).equals(Set.copyOf(mod.containedModIds())))
                throw new IOException("Client cleanup physical Mod IDs differ");
            Path jar = PathSafety.existingJarInMods(gameDir, mod.jar());
            if (!jar.getFileName().toString().equals(filename))
                throw new IOException("Selected client JAR path changed");
            BasicFileAttributes before = Files.readAttributes(jar, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            String actual = Hashing.sha512(jar);
            PathSafety.rejectReparsePoint(jar);
            BasicFileAttributes after = Files.readAttributes(jar, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!actual.equals(mod.sha512()) || before.size() != after.size()
                    || !before.lastModifiedTime().equals(after.lastModifiedTime())
                    || !Objects.equals(before.fileKey(), after.fileKey()))
                throw new IOException("Selected client JAR changed during verification");
            entries.add(new Entry(mod.modId(), mod.displayName(), mod.version(), filename,
                    mod.sha512(), mod.kind(), mod.containedModIds()));
        }
        if (!evaluatedGraph.evaluate(filenames).safe())
            throw new IOException("Client cleanup dependencies are unsafe");
        Set<String> graphFiles = report.cleanupGraph().jars().stream()
                .map(ClientCleanupGraph.PhysicalJar::filename)
                .collect(java.util.stream.Collectors.toSet());
        if (graphFiles.size() != report.cleanupGraph().jars().size()
                || !PathSafety.topLevelJarNames(gameDir).equals(graphFiles))
            throw new IOException("Current mods JAR inventory differs from FML dependency evidence");
        List<DependencyGuard> guards = new ArrayList<>();
        for (ClientCleanupGraph.PhysicalJar physical : report.cleanupGraph().jars().stream()
                .sorted(java.util.Comparator.comparing(ClientCleanupGraph.PhysicalJar::filename)).toList()) {
            Path source = PathSafety.existingJarNamedInMods(gameDir, physical.filename());
            BasicFileAttributes before = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            String hash = Hashing.sha512(source);
            PathSafety.rejectReparsePoint(source);
            BasicFileAttributes after = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (before.size() != after.size()
                    || !before.lastModifiedTime().equals(after.lastModifiedTime())
                    || !Objects.equals(before.fileKey(), after.fileKey())
                    || physical.observedSha512() == null
                    || !physical.observedSha512().equals(hash))
                throw new IOException("A client dependency JAR changed during verification");
            ClientCompatibilityReport.ExtraMod selectedMod = selected.stream()
                    .filter(mod -> mod.filename().equals(physical.filename())).findFirst().orElse(null);
            if (selectedMod != null && !hash.equals(selectedMod.sha512()))
                throw new IOException("A selected JAR differs from the dependency snapshot");
            guards.add(new DependencyGuard(physical.filename(), hash));
        }
        Path parent = PathSafety.janeDirectoryPath(gameDir, "disabled", serverId);
        Path backupParent = PathSafety.janeDirectoryPath(gameDir, "backups", serverId);
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < 60; i++) {
            String timestamp = now.plusSeconds(i).format(STAMP);
            if (!Files.exists(parent.resolve(timestamp), LinkOption.NOFOLLOW_LINKS)
                    && !Files.exists(backupParent.resolve(timestamp), LinkOption.NOFOLLOW_LINKS))
                return new ClientDisablePlan(serverId, timestamp, entries, guards, stagedFingerprint);
        }
        throw new IOException("No free disable timestamp");
    }
}
