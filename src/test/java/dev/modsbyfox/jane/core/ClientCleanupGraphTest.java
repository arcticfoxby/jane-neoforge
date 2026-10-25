package dev.modsbyfox.jane.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ClientCleanupGraphTest {
    @Test
    void retainedClientRequiredDependencyBlocksTargetButRemovingOwnerTooAllowsIt() {
        ClientCleanupGraph graph = graph(
                jar("a.jar", "owner", dep("owner", "target", "REQUIRED", "CLIENT")),
                jar("b.jar", "target"));

        var onlyTarget = graph.evaluate(Set.of("b.jar"));
        assertFalse(onlyTarget.safe());
        assertEquals(ClientCleanupGraph.Reason.REQUIRED_BY_OTHER_MOD,
                onlyTarget.decision("b.jar").block().reason());
        assertEquals("owner", onlyTarget.decision("b.jar").block().ownerModId());
        assertEquals("target", onlyTarget.decision("b.jar").block().targetModId());
        assertTrue(graph.evaluate(Set.of("a.jar", "b.jar")).safe());
        assertEquals(Set.of("a.jar", "b.jar"),
                graph.safeDefaultSelection(Set.of("a.jar", "b.jar")));
    }

    @Test
    void changingSelectionRechecksTheFinalSet() {
        ClientCleanupGraph graph = graph(
                jar("a.jar", "owner", dep("owner", "target", "REQUIRED", "BOTH")),
                jar("b.jar", "target"));
        assertTrue(graph.evaluate(Set.of("a.jar", "b.jar")).safe());
        assertFalse(graph.evaluate(Set.of("b.jar")).safe());
        assertEquals(Set.of(), graph.safeDefaultSelection(Set.of("b.jar")));
    }

    @Test
    void defaultSelectionRemovesDependencyBlockersAcrossMultiplePhysicalJars() {
        ClientCleanupGraph graph = graph(
                jar("a.jar", "alpha", dep("alpha", "beta", "REQUIRED", "CLIENT")),
                jar("b.jar", "beta", dep("beta", "gamma", "REQUIRED", "BOTH")),
                jar("c.jar", "gamma"));
        assertEquals(Set.of("a.jar", "b.jar", "c.jar"),
                graph.safeDefaultSelection(Set.of("a.jar", "b.jar", "c.jar")));
        assertEquals(Set.of(), graph.safeDefaultSelection(Set.of("b.jar", "c.jar")));
    }

    @Test
    void serverSideAndOptionalDependenciesDoNotBlockClientCleanup() {
        ClientCleanupGraph graph = graph(
                jar("a.jar", "owner",
                        dep("owner", "target", "REQUIRED", "SERVER"),
                        dep("owner", "target", "OPTIONAL", "CLIENT"),
                        dep("owner", "target", "INCOMPATIBLE", "BOTH"),
                        dep("owner", "target", "DISCOURAGED", "BOTH")),
                jar("b.jar", "target"));
        var decision = graph.evaluate(Set.of("b.jar")).decision("b.jar");
        assertTrue(decision.eligible());
        assertEquals(1, decision.optionalImpacts().size());
    }

    @Test
    void secondIdInPhysicalJarAndRetainedExternalModCanBlock() {
        ClientCleanupGraph.PhysicalJar target = new ClientCleanupGraph.PhysicalJar("bundle.jar",
                List.of("alpha", "beta"), List.of(), true);
        ClientCleanupGraph graph = new ClientCleanupGraph(List.of(target),
                List.of(new ClientCleanupGraph.RetainedMod("minecraft",
                        List.of(dep("minecraft", "beta", "REQUIRED", "BOTH")), true)), true);
        var decision = graph.evaluate(Set.of("bundle.jar")).decision("bundle.jar");
        assertFalse(decision.eligible());
        assertEquals("beta", decision.block().targetModId());
    }

    @Test
    void unknownMetadataAndUnknownSelectionFailClosed() {
        ClientCleanupGraph graph = new ClientCleanupGraph(
                List.of(jar("b.jar", "target")),
                List.of(new ClientCleanupGraph.RetainedMod("unknown", List.of(), false)), true);
        assertEquals(ClientCleanupGraph.Reason.UNKNOWN_METADATA,
                graph.evaluate(Set.of("b.jar")).decision("b.jar").block().reason());
        assertFalse(graph.evaluate(Set.of("missing.jar")).safe());
        assertEquals(ClientCleanupGraph.Reason.UNKNOWN_SELECTION,
                graph.evaluate(Set.of("missing.jar")).decision("missing.jar").block().reason());
        assertTrue(ClientCleanupGraph.unavailable().safeDefaultSelection(Set.of("b.jar")).isEmpty());
        ClientCleanupGraph jane = graph(jar("jane.jar", "jane"));
        assertEquals(ClientCleanupGraph.Reason.UNKNOWN_SELECTION,
                jane.evaluate(Set.of("jane.jar")).decision("jane.jar").block().reason());
    }

    @Test
    void unknownDependencyAndDuplicateModIdsFailClosed() {
        ClientCleanupGraph unknown = graph(
                jar("a.jar", "owner", dep("owner", "target", "UNKNOWN", "CLIENT")),
                jar("b.jar", "target"));
        assertEquals(ClientCleanupGraph.Reason.UNKNOWN_DEPENDENCY,
                unknown.evaluate(Set.of("b.jar")).decision("b.jar").block().reason());

        ClientCleanupGraph duplicate = graph(jar("a.jar", "target"), jar("b.jar", "target"));
        assertEquals(ClientCleanupGraph.Reason.DUPLICATE_MOD_ID,
                duplicate.evaluate(Set.of("a.jar")).decision("a.jar").block().reason());

        ClientCleanupGraph caseCollision = graph(
                jar("Tool.jar", "alpha"), jar("tool.jar", "beta"));
        assertEquals(ClientCleanupGraph.Reason.UNKNOWN_METADATA,
                caseCollision.evaluate(Set.of("Tool.jar")).decision("Tool.jar").block().reason());
    }

    @Test
    void graphFingerprintIncludesAllModIdsDependenciesAndCompleteness() {
        ClientCleanupGraph base = graph(jar("bundle.jar", "alpha"));
        ClientCleanupGraph moreId = new ClientCleanupGraph(List.of(new ClientCleanupGraph.PhysicalJar(
                "bundle.jar", List.of("alpha", "beta"), List.of(), true)), List.of(), true);
        ClientCleanupGraph moreDependency = graph(jar("bundle.jar", "alpha",
                dep("alpha", "minecraft", "OPTIONAL", "CLIENT")));
        ClientCleanupGraph incomplete = new ClientCleanupGraph(List.of(new ClientCleanupGraph.PhysicalJar(
                "bundle.jar", List.of("alpha"), List.of(), false)), List.of(), true);
        assertNotEquals(base.fingerprint(), moreId.fingerprint());
        assertNotEquals(base.fingerprint(), moreDependency.fingerprint());
        assertNotEquals(base.fingerprint(), incomplete.fingerprint());
        ClientCleanupGraph observed = graph(new ClientCleanupGraph.PhysicalJar(
                "bundle.jar", List.of("alpha"), List.of(), true, "a".repeat(128)));
        assertNotEquals(base.fingerprint(), observed.fingerprint());
        assertEquals(base.fingerprint(), graph(jar("bundle.jar", "alpha")).fingerprint());
    }

    private static ClientCleanupGraph graph(ClientCleanupGraph.PhysicalJar... jars) {
        return new ClientCleanupGraph(List.of(jars), List.of(), true);
    }

    private static ClientCleanupGraph.PhysicalJar jar(String filename, String id,
            ClientCleanupGraph.Dependency... dependencies) {
        return new ClientCleanupGraph.PhysicalJar(filename, List.of(id),
                List.of(dependencies), true);
    }

    private static ClientCleanupGraph.Dependency dep(String owner, String target,
            String type, String side) {
        return new ClientCleanupGraph.Dependency(owner, target,
                ClientCleanupGraph.DependencyType.valueOf(type),
                ClientCleanupGraph.DependencySide.valueOf(side), "[1,)");
    }
}
