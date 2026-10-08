package dev.modsbyfox.jane.neoforge;

import static dev.modsbyfox.jane.neoforge.ClientRequirementClassifier.EntrypointSide.BOTH;
import static dev.modsbyfox.jane.neoforge.ClientRequirementClassifier.EntrypointSide.CLIENT;
import static dev.modsbyfox.jane.neoforge.ClientRequirementClassifier.EntrypointSide.DEDICATED_SERVER;
import static dev.modsbyfox.jane.neoforge.ClientRequirementClassifier.EntrypointSide.DEFAULT;
import static dev.modsbyfox.jane.neoforge.ClientRequirementClassifier.MatchKind.EXACT_SHA512;
import static dev.modsbyfox.jane.neoforge.ClientRequirementClassifier.MatchKind.PROJECT_ONLY;
import static dev.modsbyfox.jane.neoforge.ClientRequirementClassifier.Override.NONE;
import static dev.modsbyfox.jane.neoforge.ClientRequirementClassifier.VersionEnvironment.CLIENT_REQUIRED;
import static dev.modsbyfox.jane.neoforge.ClientRequirementClassifier.VersionEnvironment.DEDICATED_SERVER_ONLY;
import static dev.modsbyfox.jane.neoforge.ClientRequirementClassifier.VersionEnvironment.SERVER_ONLY;
import static dev.modsbyfox.jane.neoforge.ClientRequirementClassifier.VersionEnvironment.SERVER_ONLY_CLIENT_OPTIONAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ClientRequirementClassifierTest {
    @Test
    void explicitOverrideWinsOverConflictingEvidence() {
        var serverOnly = fml(DEDICATED_SERVER);
        var clientRequired = version(EXACT_SHA512, CLIENT_REQUIRED);
        var forcedOut = classify(ClientRequirementClassifier.Override.NOT_REQUIRED, serverOnly, clientRequired);
        assertEquals(ClientRequirement.NOT_REQUIRED, forcedOut.requirement());
        assertTrue(forcedOut.diagnostics().stream().anyMatch(item -> item.contains("ignores")));

        var forcedIn = classify(ClientRequirementClassifier.Override.REQUIRED, serverOnly,
                version(EXACT_SHA512, SERVER_ONLY));
        assertEquals(ClientRequirement.REQUIRED, forcedIn.requirement());
        assertTrue(forcedIn.diagnostics().isEmpty());
    }

    @Test
    void onlyCompleteAllDedicatedServerEntrypointsCanProveServerOnly() {
        assertEquals(ClientRequirement.UNKNOWN, classify(NONE, fml(DEDICATED_SERVER), null).requirement());
        assertEquals(ClientRequirement.NOT_REQUIRED,
                ClientRequirementClassifier.classify(new ClientRequirementClassifier.Input(
                        NONE, fml(DEDICATED_SERVER, DEDICATED_SERVER), null,
                        verifiedEntrypoints())).requirement());
        assertEquals(ClientRequirement.UNKNOWN, classify(NONE, fml(DEDICATED_SERVER, BOTH), null).requirement());
        assertEquals(ClientRequirement.UNKNOWN, classify(NONE, fml(CLIENT), null).requirement());
        assertEquals(ClientRequirement.UNKNOWN, classify(NONE, fml(DEFAULT), null).requirement());
    }

    @Test
    void incompleteAnalysisCannotExcludeDespiteMatchingDedicatedEntrypoints() {
        var analysis = new JarAnalysisResult(List.of(new JarEvidence(
                JarEvidence.Source.BYTECODE_ANALYSIS, JarEvidence.Kind.ENTRYPOINT_MATCH,
                true, "example", "current JAR matched FML")), false,
                List.of("Embedded JAR was not analyzed"), 1, 0, 0);
        var result = ClientRequirementClassifier.classify(new ClientRequirementClassifier.Input(
                NONE, fml(DEDICATED_SERVER), null, analysis));
        assertEquals(ClientRequirement.UNKNOWN, result.requirement());
        assertTrue(result.includeInClientManifest());
    }

    @Test
    void unreadableOrMissingEntrypointDataRemainsIncluded() {
        for (var evidence : List.of(
                new ClientRequirementClassifier.FmlEvidence(false, List.of(DEDICATED_SERVER)),
                new ClientRequirementClassifier.FmlEvidence(true, List.of()))) {
            var result = classify(NONE, evidence, null);
            assertEquals(ClientRequirement.UNKNOWN, result.requirement());
            assertTrue(result.includeInClientManifest());
        }
        assertEquals(ClientRequirement.UNKNOWN, classify(NONE, null, null).requirement());
    }

    @Test
    void exactHashVersionEnvironmentCanExcludeServerOnlyFiles() {
        for (var environment : List.of(DEDICATED_SERVER_ONLY, SERVER_ONLY, SERVER_ONLY_CLIENT_OPTIONAL)) {
            var result = classify(NONE, null, version(EXACT_SHA512, environment));
            assertEquals(ClientRequirement.NOT_REQUIRED, result.requirement());
            assertFalse(result.includeInClientManifest());
        }
        assertEquals(ClientRequirement.REQUIRED,
                classify(NONE, null, version(EXACT_SHA512, CLIENT_REQUIRED)).requirement());
    }

    @Test
    void projectOnlyEnvironmentCannotExcludePhysicalJar() {
        var result = classify(NONE, null, version(PROJECT_ONLY, SERVER_ONLY));
        assertEquals(ClientRequirement.UNKNOWN, result.requirement());
        assertTrue(result.includeInClientManifest());
    }

    @Test
    void strongEvidenceConflictRequiresClientAndReportsDiagnostic() {
        var networkRequires = ClientRequirementClassifier.classify(new ClientRequirementClassifier.Input(
                NONE, fml(BOTH), version(EXACT_SHA512, SERVER_ONLY),
                new JarAnalysisResult(List.of(new JarEvidence(JarEvidence.Source.NETWORK_ANALYSIS,
                        JarEvidence.Kind.REQUIRED_NETWORK, true, "Network#register", "required payload")),
                        true, List.of(), 1, 0, 1)));
        assertEquals(ClientRequirement.REQUIRED, networkRequires.requirement());
        assertTrue(networkRequires.diagnostics().stream().anyMatch(item -> item.contains("disagree")));

        var modrinthRequires = classify(NONE, fml(DEDICATED_SERVER), version(EXACT_SHA512, CLIENT_REQUIRED));
        assertEquals(ClientRequirement.REQUIRED, modrinthRequires.requirement());
        assertFalse(modrinthRequires.diagnostics().isEmpty());

    }

    @Test
    void optionalPayloadsAndDefaultEntrypointRemainUnknownWithReason() {
        var result = ClientRequirementClassifier.classify(new ClientRequirementClassifier.Input(
                NONE, fml(DEFAULT), null,
                new JarAnalysisResult(List.of(new JarEvidence(JarEvidence.Source.NETWORK_ANALYSIS,
                        JarEvidence.Kind.OPTIONAL_NETWORK, true, "Network#register", "optional payload")),
                        true, List.of(), 1, 1, 0)));
        assertEquals(ClientRequirement.UNKNOWN, result.requirement());
        assertTrue(result.includeInClientManifest());
        assertTrue(result.reason().contains("Optional payloads"));
        assertTrue(result.evidence().stream().anyMatch(item ->
                item.source() == ClientRequirementClassifier.EvidenceSource.NETWORK_ANALYSIS));
    }

    @Test
    void confirmedRegistryContentRequiresClientDespiteIncompleteScan() {
        var result = ClientRequirementClassifier.classify(new ClientRequirementClassifier.Input(
                NONE, fml(BOTH), null,
                new JarAnalysisResult(List.of(new JarEvidence(JarEvidence.Source.REGISTRY_ANALYSIS,
                        JarEvidence.Kind.REQUIRED_REGISTRY, true, "Mod#register", "confirmed block")),
                        false, List.of("Unresolved dynamic call"), 1, 0, 0)));
        assertEquals(ClientRequirement.REQUIRED, result.requirement());
        assertFalse(result.analysisComplete());
    }

    @Test
    void dynamicRegistrationDoesNotExcludeJar() {
        var result = ClientRequirementClassifier.classify(new ClientRequirementClassifier.Input(
                NONE, fml(DEFAULT), null,
                new JarAnalysisResult(List.of(new JarEvidence(JarEvidence.Source.BYTECODE_ANALYSIS,
                        JarEvidence.Kind.DYNAMIC_OR_REFLECTIVE, false, "Mod#init", "reflection")),
                        false, List.of("Reflection cannot be traced"), 1, 0, 0)));
        assertEquals(ClientRequirement.UNKNOWN, result.requirement());
        assertTrue(result.includeInClientManifest());
        assertFalse(result.reason().isBlank());
    }

    private static ClientRequirementClassifier.FmlEvidence fml(ClientRequirementClassifier.EntrypointSide... sides) {
        return new ClientRequirementClassifier.FmlEvidence(true, List.of(sides));
    }

    private static ClientRequirementClassifier.ModrinthEvidence version(
            ClientRequirementClassifier.MatchKind match, ClientRequirementClassifier.VersionEnvironment environment) {
        return new ClientRequirementClassifier.ModrinthEvidence(match, environment);
    }

    private static ClientRequirementClassifier.Result classify(ClientRequirementClassifier.Override override,
            ClientRequirementClassifier.FmlEvidence fml, ClientRequirementClassifier.ModrinthEvidence modrinth) {
        return ClientRequirementClassifier.classify(new ClientRequirementClassifier.Input(override, fml, modrinth));
    }

    private static JarAnalysisResult verifiedEntrypoints() {
        return new JarAnalysisResult(List.of(new JarEvidence(JarEvidence.Source.BYTECODE_ANALYSIS,
                JarEvidence.Kind.ENTRYPOINT_MATCH, true, "example", "current JAR matched FML")),
                true, List.of(), 2, 0, 0);
    }
}
