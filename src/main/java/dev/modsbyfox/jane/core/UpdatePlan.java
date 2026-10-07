package dev.modsbyfox.jane.core;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public record UpdatePlan(String syncId, String serverId, String timestamp, List<Operation> operations) {
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

    public UpdatePlan {
        if (syncId == null || !syncId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") || serverId == null || !serverId.matches("[0-9a-f]{64}")
                || timestamp == null || !timestamp.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}_[0-9]{2}-[0-9]{2}-[0-9]{2}")) {
            throw new IllegalArgumentException("Invalid update identity");
        }
        operations = List.copyOf(operations);
        if (operations.isEmpty() || operations.size() > RequiredManifest.MAX_ENTRIES) throw new IllegalArgumentException("Invalid operation count");
        Set<String> names = new HashSet<>();
        Set<String> ids = new HashSet<>();
        for (Operation operation : operations) {
            if (!ids.add(operation.modId()) || !names.add(operation.newFile().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Duplicate target in update plan");
            }
        }
    }
}
