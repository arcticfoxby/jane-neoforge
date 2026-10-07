package dev.modsbyfox.jane.core;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record RequiredManifest(int protocol, List<ManifestEntry> entries) {
    public static final int PROTOCOL = 3;
    public static final int MAX_ENTRIES = 128;
    public static final int MAX_PAYLOAD = 64 * 1024;

    public RequiredManifest {
        if (protocol != PROTOCOL) throw new IllegalArgumentException("Unsupported Jane protocol");
        if (entries == null || entries.size() > MAX_ENTRIES) throw new IllegalArgumentException("Invalid manifest size");
        entries = List.copyOf(entries);
        Set<String> ids = new HashSet<>();
        for (ManifestEntry entry : entries) {
            if (entry == null || !ids.add(entry.modId())) throw new IllegalArgumentException("Duplicate or null mod ID");
        }
    }
}
