package dev.modsbyfox.jane.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Local-only inventory of physical JARs outside the server-required baseline. */
public record ClientCompatibilityReport(List<ExtraMod> explicitClientMods, List<ExtraMod> otherExtraMods,
                                        String fingerprint) {
    public enum Kind { EXPLICIT_CLIENT, OTHER_EXTRA }
    public record ExtraMod(String modId, String displayName, String version, String filename,
                           String sha512, Kind kind, Path jar) {
        public ExtraMod {
            if (!ManifestEntry.validId(modId) || displayName == null || displayName.isBlank()
                    || version == null || version.isBlank() || kind == null || jar == null
                    || (kind == Kind.EXPLICIT_CLIENT && (sha512 == null || !sha512.matches("[0-9a-f]{128}")))
                    || (kind == Kind.OTHER_EXTRA && sha512 != null))
                throw new IllegalArgumentException("Invalid extra mod");
            if (filename == null || filename.isBlank()) throw new IllegalArgumentException("Invalid extra filename");
        }
    }

    public ClientCompatibilityReport {
        explicitClientMods = List.copyOf(explicitClientMods);
        otherExtraMods = List.copyOf(otherExtraMods);
        for (ExtraMod mod : explicitClientMods)
            if (mod.kind() != Kind.EXPLICIT_CLIENT) throw new IllegalArgumentException("Incorrect explicit kind");
        for (ExtraMod mod : otherExtraMods)
            if (mod.kind() != Kind.OTHER_EXTRA) throw new IllegalArgumentException("Incorrect other kind");
        Objects.requireNonNull(fingerprint, "fingerprint");
        if (!fingerprint.matches("[0-9a-f]{128}")) throw new IllegalArgumentException("Invalid fingerprint");
    }

    public static String fingerprint(List<ExtraMod> explicit) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            for (ExtraMod mod : explicit.stream().sorted(Comparator.comparing(ExtraMod::modId)
                    .thenComparing(ExtraMod::version).thenComparing(ExtraMod::sha512)).toList()) {
                digest.update((mod.modId() + "\0" + mod.version() + "\0" + mod.sha512() + "\n")
                        .getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
