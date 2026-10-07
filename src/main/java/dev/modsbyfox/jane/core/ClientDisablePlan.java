package dev.modsbyfox.jane.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** A local-only, exact-file disable operation, separate from server-required updates. */
public record ClientDisablePlan(String serverId, String timestamp, List<Entry> entries) {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    public record Entry(String modId, String displayName, String version, String filename, String sha512) {
        public Entry {
            if (!ManifestEntry.validId(modId) || displayName == null || displayName.isBlank()
                    || version == null || version.isBlank() || sha512 == null
                    || !sha512.matches("[0-9a-f]{128}")) throw new IllegalArgumentException("Invalid disable entry");
            PathSafety.safeExistingJarName(filename);
        }
    }

    public ClientDisablePlan {
        if (serverId == null || !serverId.matches("[0-9a-f]{64}") || timestamp == null
                || !timestamp.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}_[0-9]{2}-[0-9]{2}-[0-9]{2}"))
            throw new IllegalArgumentException("Invalid disable identity");
        entries = List.copyOf(entries);
        if (entries.isEmpty() || entries.size() > RequiredManifest.MAX_ENTRIES)
            throw new IllegalArgumentException("Invalid disable entry count");
        Set<String> names = new HashSet<>();
        for (Entry entry : entries)
            if (!names.add(entry.filename().toLowerCase(Locale.ROOT)))
                throw new IllegalArgumentException("Duplicate disable filename");
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
            entries.add(new Entry(mod.modId(), mod.displayName(), mod.version(), filename, mod.sha512()));
        }
        Path parent = PathSafety.janeDirectory(gameDir, "disabled", serverId);
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < 60; i++) {
            String timestamp = now.plusSeconds(i).format(STAMP);
            if (!Files.exists(parent.resolve(timestamp), LinkOption.NOFOLLOW_LINKS))
                return new ClientDisablePlan(serverId, timestamp, entries);
        }
        throw new IOException("No free disable timestamp");
    }
}
