package dev.modsbyfox.jane.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientDisableStrictPlanTest {
    private static final String SERVER = "d".repeat(64);
    @TempDir Path game;

    @Test
    void mixedManualAndServerCleanupRequiresFinalDependencySafeSet() throws Exception {
        Path mods = Files.createDirectory(game.resolve("mods"));
        Path serverJar = Files.writeString(mods.resolve("server.jar"), "server bytes");
        Path clientJar = Files.writeString(mods.resolve("client.jar"), "client bytes");
        String serverHash = Hashing.sha512(serverJar);
        String clientHash = Hashing.sha512(clientJar);
        var dependency = new ClientCleanupGraph.Dependency("clientmod", "servermod",
                ClientCleanupGraph.DependencyType.REQUIRED, ClientCleanupGraph.DependencySide.CLIENT, "[1,)");
        ClientCleanupGraph graph = new ClientCleanupGraph(List.of(
                new ClientCleanupGraph.PhysicalJar("server.jar", List.of("servermod"), List.of(), true, serverHash),
                new ClientCleanupGraph.PhysicalJar("client.jar", List.of("clientmod"),
                        List.of(dependency), true, clientHash)), List.of(), true);
        var server = extra(serverJar, "servermod", ClientCompatibilityReport.Kind.EXPLICIT_SERVER);
        var client = extra(clientJar, "clientmod", ClientCompatibilityReport.Kind.EXPLICIT_CLIENT);
        var report = new ClientCompatibilityReport(List.of(client), List.of(server),
                ClientCompatibilityReport.fingerprint(List.of(client)), graph);
        RequiredManifest manifest = new RequiredManifest(RequiredManifest.PROTOCOL, List.of());

        assertThrows(java.io.IOException.class,
                () -> ClientDisablePlan.prepare(game, SERVER, manifest, report, List.of(server)));
        ClientDisablePlan safe = ClientDisablePlan.prepare(game, SERVER, manifest, report,
                List.of(server, client));
        assertEquals(List.of("server.jar", "client.jar"),
                safe.entries().stream().map(ClientDisablePlan.Entry::filename).toList());
        assertEquals(2, safe.dependencyGuards().size());
        assertFalse(Files.exists(game.resolve("jane")), "Preconfirmation planning must be read-only");
    }

    @Test
    void observedFmlHashAndWholePhysicalInventoryMustStillMatch() throws Exception {
        Path mods = Files.createDirectory(game.resolve("mods"));
        Path serverJar = Files.writeString(mods.resolve("server.jar"), "server bytes");
        String hash = Hashing.sha512(serverJar);
        var server = extra(serverJar, "servermod", ClientCompatibilityReport.Kind.EXPLICIT_SERVER);
        RequiredManifest manifest = new RequiredManifest(RequiredManifest.PROTOCOL, List.of());
        ClientCleanupGraph stale = new ClientCleanupGraph(List.of(
                new ClientCleanupGraph.PhysicalJar("server.jar", List.of("servermod"), List.of(),
                        true, "f".repeat(128))), List.of(), true);
        var staleReport = new ClientCompatibilityReport(List.of(), List.of(server),
                ClientCompatibilityReport.fingerprint(List.of()), stale);
        assertThrows(java.io.IOException.class,
                () -> ClientDisablePlan.prepare(game, SERVER, manifest, staleReport, List.of(server)));

        ClientCleanupGraph graph = new ClientCleanupGraph(List.of(
                new ClientCleanupGraph.PhysicalJar("server.jar", List.of("servermod"), List.of(), true, hash)),
                List.of(), true);
        var report = new ClientCompatibilityReport(List.of(), List.of(server),
                ClientCompatibilityReport.fingerprint(List.of()), graph);
        Files.writeString(mods.resolve("unknown.jar"), "new owner");
        assertThrows(java.io.IOException.class,
                () -> ClientDisablePlan.prepare(game, SERVER, manifest, report, List.of(server)));
        assertEquals("server bytes", Files.readString(serverJar));
    }

    private static ClientCompatibilityReport.ExtraMod extra(
            Path file, String id, ClientCompatibilityReport.Kind kind) throws Exception {
        return new ClientCompatibilityReport.ExtraMod(id, id, "1", file.getFileName().toString(),
                Hashing.sha512(file), kind, file, List.of(id), false,
                kind == ClientCompatibilityReport.Kind.EXPLICIT_SERVER ? "NOT_ASSESSED" : null);
    }
}
