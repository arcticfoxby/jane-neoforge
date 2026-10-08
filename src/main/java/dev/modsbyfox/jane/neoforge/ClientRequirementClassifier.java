package dev.modsbyfox.jane.neoforge;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Combines auditable evidence for one physical JAR without loading its classes. */
public final class ClientRequirementClassifier {
    private ClientRequirementClassifier() {}

    public enum Override { NONE, REQUIRED, NOT_REQUIRED }
    public enum EntrypointSide { DEDICATED_SERVER, CLIENT, BOTH, DEFAULT }
    public record FmlEvidence(boolean complete, List<EntrypointSide> entrypoints) {
        public FmlEvidence {
            entrypoints = List.copyOf(Objects.requireNonNull(entrypoints, "entrypoints"));
        }
    }
    public enum MatchKind { NONE, PROJECT_ONLY, EXACT_SHA512 }
    public enum VersionEnvironment {
        DEDICATED_SERVER_ONLY, SERVER_ONLY, SERVER_ONLY_CLIENT_OPTIONAL, CLIENT_REQUIRED, UNKNOWN
    }
    public record ModrinthEvidence(MatchKind matchKind, VersionEnvironment environment) {
        public ModrinthEvidence {
            Objects.requireNonNull(matchKind, "matchKind");
            Objects.requireNonNull(environment, "environment");
        }
    }
    public enum EvidenceSource {
        USER_OVERRIDE, FML_METADATA, BYTECODE_ANALYSIS, NETWORK_ANALYSIS,
        REGISTRY_ANALYSIS, MODRINTH_EXACT_HASH
    }
    public record DecisionEvidence(EvidenceSource source, String type, ClientRequirement outcome,
                                   boolean complete, String detail) {
        public DecisionEvidence {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(detail, "detail");
        }
    }
    /** Null evidence means the corresponding source was unavailable. */
    public record Input(Override override, FmlEvidence fml, ModrinthEvidence modrinth,
                        JarAnalysisResult jarAnalysis) {
        public Input {
            Objects.requireNonNull(override, "override");
        }
        public Input(Override override, FmlEvidence fml, ModrinthEvidence modrinth) {
            this(override, fml, modrinth, null);
        }
    }
    public record Result(ClientRequirement requirement, String reason, List<DecisionEvidence> evidence,
                         boolean analysisComplete, List<String> diagnostics) {
        public Result {
            Objects.requireNonNull(requirement, "requirement");
            if (reason == null || reason.isBlank()) throw new IllegalArgumentException("Missing decision reason");
            evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
            diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
        }
        /** UNKNOWN fails closed and stays in the required manifest. */
        public boolean includeInClientManifest() {
            return requirement != ClientRequirement.NOT_REQUIRED;
        }
    }

    public static Result classify(Input input) {
        Objects.requireNonNull(input, "input");
        List<DecisionEvidence> evidence = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        FmlEvidence fml = input.fml();
        JarAnalysisResult analysis = input.jarAnalysis();
        boolean fmlAllServerEntrypoints = fml != null && fml.complete() && !fml.entrypoints().isEmpty()
                && fml.entrypoints().stream().allMatch(side -> side == EntrypointSide.DEDICATED_SERVER);
        boolean complete = fml != null && fml.complete() && analysis != null && analysis.complete();
        boolean allServerEntrypoints = fmlAllServerEntrypoints && complete
                && analysis.has(JarEvidence.Kind.ENTRYPOINT_MATCH);
        if (fml != null) {
            evidence.add(new DecisionEvidence(EvidenceSource.FML_METADATA, "ENTRYPOINTS",
                    allServerEntrypoints ? ClientRequirement.NOT_REQUIRED : ClientRequirement.UNKNOWN,
                    fml.complete(), fml.entrypoints() + "; current JAR verified=" + allServerEntrypoints));
        }
        boolean networkRequired = false;
        boolean registryRequired = false;
        if (analysis != null) {
            for (JarEvidence item : analysis.evidence()) {
                ClientRequirement outcome = switch (item.kind()) {
                    case REQUIRED_NETWORK, REQUIRED_REGISTRY -> ClientRequirement.REQUIRED;
                    default -> ClientRequirement.UNKNOWN;
                };
                evidence.add(new DecisionEvidence(EvidenceSource.valueOf(item.source().name()),
                        item.kind().name(), outcome, item.complete(),
                        item.location() + ": " + item.detail()));
                networkRequired |= item.kind() == JarEvidence.Kind.REQUIRED_NETWORK;
                registryRequired |= item.kind() == JarEvidence.Kind.REQUIRED_REGISTRY;
            }
            diagnostics.addAll(analysis.diagnostics());
        }
        ModrinthEvidence modrinth = input.modrinth();
        ClientRequirement exactEnvironment = fromModrinth(modrinth);
        if (modrinth != null) {
            evidence.add(new DecisionEvidence(EvidenceSource.MODRINTH_EXACT_HASH,
                    "VERSION_ENVIRONMENT", exactEnvironment,
                    modrinth.matchKind() == MatchKind.EXACT_SHA512,
                    modrinth.matchKind() + ": " + modrinth.environment()));
        }
        boolean strongRequired = networkRequired || registryRequired || exactEnvironment == ClientRequirement.REQUIRED;
        boolean strongNotRequired = allServerEntrypoints || exactEnvironment == ClientRequirement.NOT_REQUIRED;
        if (input.override() != Override.NONE) {
            ClientRequirement forced = input.override() == Override.REQUIRED
                    ? ClientRequirement.REQUIRED : ClientRequirement.NOT_REQUIRED;
            evidence.add(new DecisionEvidence(EvidenceSource.USER_OVERRIDE, "EXPLICIT_OVERRIDE", forced, true,
                    input.override().name()));
            if (forced == ClientRequirement.NOT_REQUIRED && strongRequired)
                diagnostics.add("Explicit NOT_REQUIRED override ignores confirmed client-required evidence");
            return new Result(forced, "Explicit user override: " + input.override(), evidence, complete, diagnostics);
        }
        if (strongRequired && (fmlAllServerEntrypoints || exactEnvironment == ClientRequirement.NOT_REQUIRED))
            diagnostics.add("Confirmed client-required evidence and server-only evidence disagree; requiring the JAR on clients");
        if (strongRequired) {
            String reason = networkRequired ? "Required NeoForge payload registration was confirmed"
                    : registryRequired ? "Client-required registry content was confirmed"
                    : "Exact-SHA-512 Modrinth version requires clients";
            return new Result(ClientRequirement.REQUIRED, reason, evidence, complete, diagnostics);
        }
        if (strongNotRequired) {
            String reason = allServerEntrypoints ? "All FML mod entrypoints are dedicated-server-only"
                    : "Exact-SHA-512 Modrinth version declares the client installation optional";
            return new Result(ClientRequirement.NOT_REQUIRED, reason, evidence, complete, diagnostics);
        }
        String reason;
        if (analysis == null)
            reason = "Physical JAR analysis is unavailable and no conclusive requirement evidence exists";
        else if (fmlAllServerEntrypoints && !allServerEntrypoints)
            reason = "FML dedicated-server entrypoints could not be verified against the current physical JAR"
                    + (analysis.diagnostics().isEmpty() ? "" : ": " + analysis.diagnostics().getFirst());
        else if (analysis.optionalPayloads() > 0
                && analysis.has(JarEvidence.Kind.POTENTIAL_UNGUARDED_SEND))
            reason = "Optional payloads were observed, but clientbound sends may lack a negotiated-channel guard";
        else if (analysis.optionalPayloads() > 0 && !analysis.complete())
            reason = "Optional payloads were observed, but incomplete analysis cannot prove client installation is optional";
        else if (!analysis.complete())
            reason = "Physical JAR analysis is incomplete: "
                    + (analysis.diagnostics().isEmpty() ? "no conclusive requirement evidence exists"
                            : analysis.diagnostics().getFirst());
        else if (fml == null || !fml.complete())
            reason = "FML entrypoint metadata is incomplete and no conclusive requirement evidence exists";
        else if (analysis.optionalPayloads() > 0)
            reason = "Optional payloads and the observed entrypoints do not prove whether the client needs this JAR";
        else
            reason = "Observed entrypoints and bytecode do not prove whether the client needs this JAR";
        diagnostics.add(reason);
        return new Result(ClientRequirement.UNKNOWN, reason, evidence, complete, diagnostics);
    }

    private static ClientRequirement fromModrinth(ModrinthEvidence evidence) {
        if (evidence == null || evidence.matchKind() != MatchKind.EXACT_SHA512)
            return ClientRequirement.UNKNOWN;
        return switch (evidence.environment()) {
            case DEDICATED_SERVER_ONLY, SERVER_ONLY, SERVER_ONLY_CLIENT_OPTIONAL -> ClientRequirement.NOT_REQUIRED;
            case CLIENT_REQUIRED -> ClientRequirement.REQUIRED;
            case UNKNOWN -> ClientRequirement.UNKNOWN;
        };
    }
}
