package dev.modsbyfox.jane.neoforge;

import java.util.List;
import java.util.Objects;

/** Static observations; the classifier makes the final client requirement decision. */
public record JarAnalysisResult(List<JarEvidence> evidence, boolean complete, List<String> diagnostics,
                                int scannedClasses, int optionalPayloads, int requiredPayloads) {
    public JarAnalysisResult {
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
        if (evidence.size() > 1024 || diagnostics.size() > 128 || scannedClasses < 0
                || optionalPayloads < 0 || requiredPayloads < 0) {
            throw new IllegalArgumentException("Invalid JAR analysis result size or count");
        }
        if (diagnostics.stream().anyMatch(message -> message == null || message.length() > 1024)) {
            throw new IllegalArgumentException("Invalid JAR analysis diagnostic");
        }
    }

    public boolean has(JarEvidence.Kind kind) {
        return evidence.stream().anyMatch(item -> item.kind() == kind);
    }
}
