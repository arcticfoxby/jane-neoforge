package dev.modsbyfox.jane.core;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SelectiveStagingTest {
    @TempDir Path game;

    @Test void unselectedUnresolvedFileDoesNotEnterPendingPlan() throws Exception {
        Files.createDirectory(game.resolve("mods"));
        byte[] bytes = "verified selected JAR".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path source = Files.write(game.resolve("source.jar"), bytes);
        ManifestEntry selected = new ManifestEntry("selected", "Selected", "1", bytes.length,
                Hashing.sha512(source));
        ManifestEntry skipped = new ManifestEntry("skipped", "Skipped", "1", 10,
                "a".repeat(128));
        RequiredManifest manifest = new RequiredManifest(RequiredManifest.PROTOCOL, List.of(selected, skipped));
        var compared = Comparison.compare(manifest, Map.of(), path -> "");
        JaneSyncSession session = new JaneSyncSession(new PendingSyncContext("example.org", manifest, compared));
        session.publishResolution(ResolutionPlan.resolve(compared, entry -> entry.modId().equals("selected")
                ? Optional.of(new ResolutionPlan.Source("selected.jar",
                    URI.create("https://cdn.modrinth.com/test.jar"), entry.fileSize()))
                : Optional.empty()));
        assertTrue(session.selectModIds(Set.of("selected")));
        assertEquals(List.of("selected"), session.snapshot().queue(ResolutionPlan.TransferGroup.TRUSTED)
                .stream().map(item -> item.comparison().required().modId()).toList());
        assertTrue(session.startTransfer(ResolutionPlan.TransferGroup.TRUSTED,
                ResolutionPlan.TransferRoute.TRUSTED_SOURCE));
        StagingWorkspace workspace = StagingWorkspace.create(game);
        Files.write(workspace.directory().resolve("selected.jar"), bytes);
        workspace.add(new UpdatePlan.Operation("selected", UpdatePlan.Kind.ADD, null, null,
                "selected.jar", selected.sha512(), selected.fileSize()));
        session.update("selected", JaneSyncSession.RuntimeState.READY, selected.fileSize());
        session.finishTransfer(false, null);
        assertTrue(session.snapshot().canInstall());
        UpdatePlan plan = workspace.prepareIfComplete(game, session, () -> false,
                session.snapshot().selectedModIds());
        assertNotNull(plan);
        assertEquals(List.of("selected"), plan.operations().stream().map(UpdatePlan.Operation::modId).toList());
    }
}
