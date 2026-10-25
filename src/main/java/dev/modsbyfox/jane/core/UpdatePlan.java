package dev.modsbyfox.jane.core;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public record UpdatePlan(String syncId, String serverId, String timestamp, List<Operation> operations,
                         List<ClientDisablePlan.Entry> cleanupEntries,
                         List<ClientDisablePlan.DependencyGuard> dependencyGuards,
                         String stagedGraphFingerprint) {
    public enum Kind { ADD, REPLACE }
    public record Operation(String modId, Kind kind, String oldFile, String oldHash,
                            String newFile, String newHash, long size) {
        public Operation {
            if (!ManifestEntry.validId(modId) || kind == null) throw new IllegalArgumentException("Invalid operation");
            if (kind == Kind.REPLACE) {
                PathSafety.safeExistingJarName(oldFile);
                if (oldHash == null || !oldHash.matches("[0-9a-f]{128}")) throw new IllegalArgumentException("Invalid old hash");
            } else if (oldFile != null || oldHash != null) {
                throw new IllegalArgumentException("ADD cannot have an old JAR");
            }
            PathSafety.safeJarName(newFile);
            if (newHash == null || !newHash.matches("[0-9a-f]{128}") || size <= 0 || size > ManifestEntry.MAX_FILE_SIZE) {
                throw new IllegalArgumentException("Invalid new JAR");
            }
        }
    }

    public UpdatePlan(String syncId, String serverId, String timestamp, List<Operation> operations) {
        this(syncId, serverId, timestamp, operations, List.of(), List.of(), null);
    }

    public UpdatePlan(String syncId, String serverId, String timestamp, List<Operation> operations,
                      List<ClientDisablePlan.Entry> cleanupEntries) {
        this(syncId, serverId, timestamp, operations, cleanupEntries, List.of(), null);
    }

    public UpdatePlan(String syncId, String serverId, String timestamp, List<Operation> operations,
                      List<ClientDisablePlan.Entry> cleanupEntries,
                      List<ClientDisablePlan.DependencyGuard> dependencyGuards) {
        this(syncId, serverId, timestamp, operations, cleanupEntries, dependencyGuards, null);
    }

    public UpdatePlan {
        if (syncId == null || !syncId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") || serverId == null || !serverId.matches("[0-9a-f]{64}")
                || timestamp == null || !timestamp.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}_[0-9]{2}-[0-9]{2}-[0-9]{2}")) {
            throw new IllegalArgumentException("Invalid update identity");
        }
        operations = List.copyOf(operations);
        cleanupEntries = List.copyOf(cleanupEntries);
        dependencyGuards = List.copyOf(dependencyGuards);
        if ((operations.isEmpty() && cleanupEntries.isEmpty())
                || operations.size() > RequiredManifest.MAX_ENTRIES
                || cleanupEntries.size() > RequiredManifest.MAX_ENTRIES)
            throw new IllegalArgumentException("Invalid operation count");
        if (stagedGraphFingerprint != null && !stagedGraphFingerprint.matches("[0-9a-f]{128}"))
            throw new IllegalArgumentException("Invalid staged dependency proof");
        if (!operations.isEmpty() && !cleanupEntries.isEmpty() && stagedGraphFingerprint == null)
            throw new IllegalArgumentException("Combined install and cleanup requires staged dependency proof");
        if (operations.isEmpty() && stagedGraphFingerprint != null)
            throw new IllegalArgumentException("Cleanup-only plan cannot claim staged dependency proof");
        Set<String> names = new HashSet<>();
        Set<String> ids = new HashSet<>();
        for (Operation operation : operations) {
            if (!ids.add(operation.modId()) || !names.add(operation.newFile().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Duplicate target in update plan");
            }
        }
        for (ClientDisablePlan.Entry entry : cleanupEntries) {
            if (!names.add(entry.filename().toLowerCase(Locale.ROOT)))
                throw new IllegalArgumentException("Cleanup conflicts with installed JAR");
            for (Operation operation : operations) {
                if (entry.filename().equalsIgnoreCase(operation.oldFile()))
                    throw new IllegalArgumentException("Cleanup conflicts with replaced JAR");
            }
        }
        Set<String> guardedNames = new HashSet<>();
        for (ClientDisablePlan.DependencyGuard guard : dependencyGuards)
            if (!guardedNames.add(guard.filename().toLowerCase(Locale.ROOT)))
                throw new IllegalArgumentException("Duplicate dependency guard");
        for (ClientDisablePlan.Entry entry : cleanupEntries) {
            if (entry.kind() == ClientCompatibilityReport.Kind.EXPLICIT_SERVER
                    && dependencyGuards.stream().noneMatch(guard -> guard.filename().equals(entry.filename())
                    && guard.sha512().equals(entry.sha512())))
                throw new IllegalArgumentException("Server-entry cleanup lacks exact dependency guard");
        }
    }

    public UpdatePlan withCleanup(List<ClientDisablePlan.Entry> entries) {
        return new UpdatePlan(syncId, serverId, timestamp, operations, entries, List.of(), null);
    }

    public UpdatePlan withCleanup(ClientDisablePlan cleanup) {
        if (!serverId.equals(cleanup.serverId())) throw new IllegalArgumentException("Cleanup server differs");
        return new UpdatePlan(syncId, serverId, timestamp, operations,
                cleanup.entries(), cleanup.dependencyGuards(), cleanup.stagedGraphFingerprint());
    }
}
