package dev.modsbyfox.jane.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ClientCleanupSelectionTest {
    private static final PendingSyncContext CONTEXT = new PendingSyncContext("example.org",
            new RequiredManifest(RequiredManifest.PROTOCOL, List.of()), List.of());

    private static ClientCompatibilityReport.ExtraMod extra(String id, String hash) {
        return new ClientCompatibilityReport.ExtraMod(id, id, "1", id + ".jar", hash,
                ClientCompatibilityReport.Kind.EXPLICIT_SERVER, Path.of("mods", id + ".jar"),
                List.of(id), true, null);
    }

    private static ClientCompatibilityReport report(String bHash) {
        var graph = new ClientCleanupGraph(List.of(
                new ClientCleanupGraph.PhysicalJar("a.jar", List.of("a"), List.of(
                        new ClientCleanupGraph.Dependency("a", "b",
                                ClientCleanupGraph.DependencyType.REQUIRED,
                                ClientCleanupGraph.DependencySide.CLIENT, "[1,)")), true),
                new ClientCleanupGraph.PhysicalJar("b.jar", List.of("b"), List.of(), true)),
                List.of(), true);
        return new ClientCompatibilityReport(List.of(), List.of(extra("a", "a".repeat(128)),
                extra("b", bHash)), ClientCompatibilityReport.fingerprint(List.of()), graph);
    }

    @Test void defaultAndDeselectReevaluateFinalPhysicalRemovalSet() {
        ClientCleanupSelection selection = new ClientCleanupSelection(CONTEXT, report("b".repeat(128)));
        assertEquals(Set.of("a.jar", "b.jar"), selection.selectedFilenames());
        assertTrue(selection.setSelected("a.jar", false));
        assertEquals(Set.of(), selection.selectedFilenames(),
                "Keeping dependent A must also remove B from the selected cleanup set");
        assertFalse(selection.setSelected("b.jar", true));
        assertTrue(selection.setSelected("a.jar", true));
        assertTrue(selection.setSelected("b.jar", true));
        assertEquals(Set.of("a.jar", "b.jar"), selection.selectedFilenames());
    }

    @Test void confirmationSnapshotBindsRevisionHashAndGraph() {
        ClientCompatibilityReport report = report("b".repeat(128));
        ClientCleanupSelection selection = new ClientCleanupSelection(CONTEXT, report);
        ClientCleanupSelection.Snapshot snapshot = selection.snapshot();
        assertTrue(selection.stillMatches(snapshot, CONTEXT, report));
        assertFalse(selection.stillMatches(snapshot, CONTEXT, report("c".repeat(128))));
        assertTrue(selection.setSelected("a.jar", false));
        assertFalse(selection.stillMatches(snapshot, CONTEXT, report));
    }

    @Test void incompleteGraphNeverSelectsServerEntryByDefault() {
        var mod = extra("server", "d".repeat(128));
        var report = new ClientCompatibilityReport(List.of(), List.of(mod),
                ClientCompatibilityReport.fingerprint(List.of()), ClientCleanupGraph.unavailable());
        ClientCleanupSelection selection = new ClientCleanupSelection(CONTEXT, report);
        assertTrue(selection.selectedFilenames().isEmpty());
        assertFalse(selection.setSelected("server.jar", true));
    }

    @Test void allRequiredMatchedStillProducesCleanupOnlyPlan() {
        ManifestEntry required = new ManifestEntry("required", "Required", "1", 1, "e".repeat(128));
        PendingSyncContext context = new PendingSyncContext("example.org",
                new RequiredManifest(RequiredManifest.PROTOCOL, List.of(required)),
                List.of(new Comparison.Result(required,
                        new Comparison.LocalMod("required", "1", Path.of("required.jar")),
                        Comparison.Status.OK, required.sha512())));
        ClientCleanupSelection selection = new ClientCleanupSelection(context, report("b".repeat(128)));
        assertTrue(Comparison.passed(context.results()));
        var cleanup = selection.selectedExtras().stream().map(mod -> new ClientDisablePlan.Entry(
                mod.modId(), mod.displayName(), mod.version(), mod.filename(), mod.sha512(),
                mod.kind(), mod.containedModIds())).toList();
        var guards = cleanup.stream().map(entry ->
                new ClientDisablePlan.DependencyGuard(entry.filename(), entry.sha512())).toList();
        UpdatePlan plan = new UpdatePlan("00000000-0000-0000-0000-000000000001", context.serverId(),
                "2026-10-26_12-00-00", List.of(), cleanup, guards);
        assertTrue(plan.operations().isEmpty());
        assertEquals(Set.of("a.jar", "b.jar"), plan.cleanupEntries().stream()
                .map(ClientDisablePlan.Entry::filename).collect(java.util.stream.Collectors.toSet()));
    }

    @Test void universalExtraRequiresPhysicalProofAndManualSelection() {
        String hash = "f".repeat(128);
        var identity = new ClientCompatibilityReport.FileIdentity(16,
                FileTime.from(Instant.parse("2026-01-01T00:00:00Z")), "physical-1");
        var universal = new ClientCompatibilityReport.ExtraMod("universal", "Universal", "1",
                "universal.jar", hash, ClientCompatibilityReport.Kind.OTHER_EXTRA,
                Path.of("mods", "universal.jar"), List.of("universal"), true, null, identity);
        var graph = new ClientCleanupGraph(List.of(new ClientCleanupGraph.PhysicalJar(
                "universal.jar", List.of("universal"), List.of(), true, hash)), List.of(), true);
        var report = new ClientCompatibilityReport(List.of(), List.of(universal),
                ClientCompatibilityReport.fingerprint(List.of()), graph);
        ClientCleanupSelection selection = new ClientCleanupSelection(CONTEXT, report);
        assertTrue(selection.selectedFilenames().isEmpty(), "Universal extras must never be preselected");
        assertTrue(selection.cleanupDecision("universal.jar").eligible());
        assertTrue(selection.setSelected("universal.jar", true));
        ClientCleanupSelection.Snapshot approved = selection.snapshot();
        assertTrue(selection.stillMatches(approved, CONTEXT, report));
        var changed = new ClientCompatibilityReport.ExtraMod("universal", "Universal", "1",
                "universal.jar", hash, ClientCompatibilityReport.Kind.OTHER_EXTRA,
                Path.of("mods", "universal.jar"), List.of("universal"), true, null,
                new ClientCompatibilityReport.FileIdentity(17, identity.modifiedTime(), "physical-2"));
        var changedReport = new ClientCompatibilityReport(List.of(), List.of(changed),
                ClientCompatibilityReport.fingerprint(List.of()), graph);
        assertFalse(selection.stillMatches(approved, CONTEXT, changedReport));
    }

    @Test void universalExtraWithIncompleteOrMismatchedEvidenceCannotBeSelected() {
        String hash = "f".repeat(128);
        var identity = new ClientCompatibilityReport.FileIdentity(16,
                FileTime.from(Instant.parse("2026-01-01T00:00:00Z")), "physical-1");
        var universal = new ClientCompatibilityReport.ExtraMod("universal", "Universal", "1",
                "universal.jar", hash, ClientCompatibilityReport.Kind.OTHER_EXTRA,
                Path.of("mods", "universal.jar"), List.of("universal"), true, null, identity);
        var missingGraph = new ClientCompatibilityReport(List.of(), List.of(universal),
                ClientCompatibilityReport.fingerprint(List.of()), ClientCleanupGraph.unavailable());
        assertFalse(new ClientCleanupSelection(CONTEXT, missingGraph).setSelected("universal.jar", true));
        var staleGraph = new ClientCleanupGraph(List.of(new ClientCleanupGraph.PhysicalJar(
                "universal.jar", List.of("universal"), List.of(), true, "e".repeat(128))), List.of(), true);
        var staleReport = new ClientCompatibilityReport(List.of(), List.of(universal),
                ClientCompatibilityReport.fingerprint(List.of()), staleGraph);
        assertFalse(new ClientCleanupSelection(CONTEXT, staleReport).setSelected("universal.jar", true));
    }
}
