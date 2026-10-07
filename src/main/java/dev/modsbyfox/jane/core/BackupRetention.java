package dev.modsbyfox.jane.core;

import java.util.List;

public final class BackupRetention {
    private BackupRetention() { }

    /** Caller supplies successful backup folder names for one server only. */
    public static List<String> expired(List<String> successful, int keep) {
        if (keep < 1) throw new IllegalArgumentException("keep must be positive");
        List<String> sorted = successful.stream().sorted().toList();
        return sorted.subList(0, Math.max(0, sorted.size() - keep));
    }
}
