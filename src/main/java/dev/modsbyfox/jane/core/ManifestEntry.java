package dev.modsbyfox.jane.core;

import java.util.Locale;
import java.util.regex.Pattern;

public record ManifestEntry(String modId, String displayName, String version, long fileSize, String sha512) {
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_-]{0,63}");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{128}");
    public static final long MAX_FILE_SIZE = 512L * 1024 * 1024;

    public ManifestEntry {
        if (modId == null || !ID.matcher(modId).matches() || "jane".equals(modId)) {
            throw new IllegalArgumentException("Invalid required mod ID");
        }
        if (displayName == null || displayName.isBlank() || displayName.length() > 128 || hasControl(displayName)) {
            throw new IllegalArgumentException("Invalid display name");
        }
        if (version == null || version.isBlank() || version.length() > 64 || hasControl(version)) {
            throw new IllegalArgumentException("Invalid version");
        }
        if (fileSize <= 0 || fileSize > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("Invalid JAR size");
        }
        if (sha512 == null || !HASH.matcher(sha512).matches()) {
            throw new IllegalArgumentException("Invalid SHA-512");
        }
    }

    public static boolean validId(String value) {
        return value != null && ID.matcher(value).matches() && !"jane".equals(value);
    }

    public static String normalizeHash(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private static boolean hasControl(String value) {
        return value.chars().anyMatch(c -> Character.isISOControl(c));
    }
}
