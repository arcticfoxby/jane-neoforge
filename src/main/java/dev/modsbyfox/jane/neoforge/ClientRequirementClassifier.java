package dev.modsbyfox.jane.neoforge;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Combines explicit policy, resolved FML entrypoints, and an exact-file Modrinth
 * version match. Adapters are responsible for collecting and validating evidence;
 * this class has no loader or network dependencies.
 */
public final class ClientRequirementClassifier {
    private ClientRequirementClassifier() {}

    public enum Override {
        NONE,
        REQUIRED,
        NOT_REQUIRED
    }

    /** An effective distribution for one valid top-level {@code @Mod} entrypoint. */
    public enum EntrypointSide {
        DEDICATED_SERVER,
        CLIENT,
        BOTH,
        /** The annotation omitted a side restriction. */
        DEFAULT
    }

    /**
     * {@code complete} means every valid entrypoint in the physical JAR was read.
     * An empty list cannot prove that a JAR is server-only, even when complete.
     */
    public record FmlEvidence(boolean complete, List<EntrypointSide> entrypoints) {
        public FmlEvidence {
            entrypoints = List.copyOf(Objects.requireNonNull(entrypoints, "entrypoints"));
        }
    }

    public enum MatchKind {
        NONE,
        PROJECT_ONLY,
        EXACT_SHA512
    }

    /**
     * Normalized environment from a Modrinth version response. Only an exact
     * SHA-512 match to this physical JAR can be used to exclude it.
     */
    public enum VersionEnvironment {
        DEDICATED_SERVER_ONLY,
        SERVER_ONLY,
        SERVER_ONLY_CLIENT_OPTIONAL,
        CLIENT_REQUIRED,
        UNKNOWN
    }

    public record ModrinthEvidence(MatchKind matchKind, VersionEnvironment environment) {
        public ModrinthEvidence {
            Objects.requireNonNull(matchKind, "matchKind");
            Objects.requireNonNull(environment, "environment");
        }
    }

    /** Null evidence means the corresponding source was unavailable. */
    public record Input(Override override, FmlEvidence fml, ModrinthEvidence modrinth) {
        public Input {
            Objects.requireNonNull(override, "override");
        }
    }

    public record Result(ClientRequirement requirement, List<String> diagnostics) {
        public Result {
            Objects.requireNonNull(requirement, "requirement");
            diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
        }

        /** An unknown classification fails closed by remaining in the manifest. */
        public boolean includeInClientManifest() {
            return requirement != ClientRequirement.NOT_REQUIRED;
        }
    }

    public static Result classify(Input input) {
        Objects.requireNonNull(input, "input");
        if (input.override() == Override.REQUIRED) {
            return new Result(ClientRequirement.REQUIRED, List.of());
        }
        if (input.override() == Override.NOT_REQUIRED) {
            return new Result(ClientRequirement.NOT_REQUIRED, List.of());
        }

        ClientRequirement fml = fromFml(input.fml());
        ClientRequirement modrinth = fromModrinth(input.modrinth());
        List<String> diagnostics = new ArrayList<>();
        if (fml != ClientRequirement.UNKNOWN && modrinth != ClientRequirement.UNKNOWN && fml != modrinth) {
            diagnostics.add("FML entrypoints and exact-hash Modrinth version environment disagree; requiring the mod on clients");
            return new Result(ClientRequirement.REQUIRED, diagnostics);
        }
        if (fml == ClientRequirement.REQUIRED || modrinth == ClientRequirement.REQUIRED) {
            return new Result(ClientRequirement.REQUIRED, diagnostics);
        }
        if (fml == ClientRequirement.NOT_REQUIRED || modrinth == ClientRequirement.NOT_REQUIRED) {
            return new Result(ClientRequirement.NOT_REQUIRED, diagnostics);
        }
        return new Result(ClientRequirement.UNKNOWN, diagnostics);
    }

    private static ClientRequirement fromFml(FmlEvidence evidence) {
        if (evidence == null) return ClientRequirement.UNKNOWN;
        for (EntrypointSide side : evidence.entrypoints()) {
            if (side != EntrypointSide.DEDICATED_SERVER) {
                return ClientRequirement.REQUIRED;
            }
        }
        return evidence.complete() && !evidence.entrypoints().isEmpty()
                ? ClientRequirement.NOT_REQUIRED : ClientRequirement.UNKNOWN;
    }

    private static ClientRequirement fromModrinth(ModrinthEvidence evidence) {
        if (evidence == null || evidence.matchKind() != MatchKind.EXACT_SHA512) {
            return ClientRequirement.UNKNOWN;
        }
        return switch (evidence.environment()) {
            case DEDICATED_SERVER_ONLY, SERVER_ONLY, SERVER_ONLY_CLIENT_OPTIONAL -> ClientRequirement.NOT_REQUIRED;
            case CLIENT_REQUIRED -> ClientRequirement.REQUIRED;
            case UNKNOWN -> ClientRequirement.UNKNOWN;
        };
    }
}
