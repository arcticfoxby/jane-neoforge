package dev.modsbyfox.jane.neoforge;

import java.util.Objects;

/** One bounded, auditable observation about a physical mod JAR. */
public record JarEvidence(Source source, Kind kind, boolean complete, String location, String detail) {
    public enum Source {
        FML_METADATA,
        BYTECODE_ANALYSIS,
        NETWORK_ANALYSIS,
        REGISTRY_ANALYSIS
    }

    public enum Kind {
        ENTRYPOINT,
        ENTRYPOINT_MATCH,
        DEPENDENCY_METADATA,
        REQUIRED_NETWORK,
        OPTIONAL_NETWORK,
        REQUIRED_REGISTRY,
        DYNAMIC_OR_REFLECTIVE,
        UNRESOLVED_REGISTRATION,
        CONFIGURATION_TASK,
        POTENTIAL_UNGUARDED_SEND
    }

    public JarEvidence {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(detail, "detail");
        if (location.length() > 512 || detail.length() > 1024) {
            throw new IllegalArgumentException("JAR evidence text is too long");
        }
    }
}
