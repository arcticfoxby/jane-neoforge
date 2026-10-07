package dev.modsbyfox.jane.core;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CombinedStagingTest {
    @TempDir Path game;

    private void combined(int publicCount, int serverCount) throws Exception {
        Files.createDirectory(game.resolve("mods"));
        List<ManifestEntry> entries = new ArrayList<>();
        List<byte[]> data = new ArrayList<>();
        for (int i = 0; i < publicCount + serverCount; i++) {
            byte[] bytes = ("fixture-" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Path fixture = game.resolve("fixture-" + i + ".jar");
            Files.write(fixture, bytes);
            data.add(bytes);
            entries.add(new ManifestEntry("mod" + i, "Mod " + i, "1", bytes.length, Hashing.sha512(fixture)));
        }
        RequiredManifest manifest = new RequiredManifest(RequiredManifest.PROTOCOL, entries);
        List<Comparison.Result> comparisons = Comparison.compare(manifest, Map.of(), path -> "");
        JaneSyncSession session = new JaneSyncSession(new PendingSyncContext("example.org", manifest, comparisons,
                new ServerProviderOffer(25566, "a".repeat(64))));
        ResolutionPlan plan = ResolutionPlan.resolve(comparisons, entry -> {
            int index = Integer.parseInt(entry.modId().substring(3));
            return index < publicCount ? Optional.of(new ResolutionPlan.Source(entry.modId() + ".jar",
                    URI.create("https://cdn.modrinth.com/test.jar"), entry.fileSize())) : Optional.empty();
        }, true, count -> { });
        session.publishResolution(plan);
        assertEquals(publicCount, plan.downloadQueue().size());
        assertEquals(serverCount, plan.queue(ResolutionPlan.Classification.SERVER_DOWNLOADABLE).size());
        StagingWorkspace workspace = StagingWorkspace.create(game);
        assertTrue(session.startDownloads());
        for (int i = 0; i < publicCount; i++) ready(session, workspace, entries.get(i), data.get(i), plan.items().get(i));
        assertNull(workspace.prepareIfComplete(game, session));
        session.finish(null);
        assertEquals(SyncNotice.Kind.SERVER_ONLY_REMAINING, SyncNotice.select(session.snapshot(), false));
        assertFalse(Files.exists(game.resolve("jane/pending/" + workspace.syncId())));
        assertEquals(publicCount, session.snapshot().readyCount());
        if (serverCount > 0) {
            assertTrue(session.startServerOnlyConfirmed());
            for (int i = publicCount; i < entries.size(); i++) ready(session, workspace, entries.get(i), data.get(i), plan.items().get(i));
        }
        UpdatePlan update = workspace.prepareIfComplete(game, session);
        assertNotNull(update);
        assertEquals(publicCount + serverCount, update.operations().size());
        assertEquals(workspace.syncId(), update.syncId());
        assertTrue(update.operations().stream().allMatch(op -> op.kind() == UpdatePlan.Kind.ADD));
        assertTrue(Files.exists(game.resolve("jane/pending/" + workspace.syncId() + "/pending.json")));
        session.finish(null);
        assertTrue(session.snapshot().canInstall());
    }

    private void ready(JaneSyncSession session, StagingWorkspace workspace, ManifestEntry entry, byte[] data,
                       ResolutionPlan.Item item) throws Exception {
        Path staged = workspace.directory().resolve(item.source().name());
        Files.write(staged, data);
        session.update(entry.modId(), JaneSyncSession.RuntimeState.READY, data.length);
        workspace.add(new UpdatePlan.Operation(entry.modId(), UpdatePlan.Kind.ADD, null, null,
                item.source().name(), entry.sha512(), entry.fileSize()));
    }

    @Test void twoPublicOneServerShareOnePendingPlan() throws Exception { combined(2, 1); }
    @Test void thirtyEightPublicThreeServerShareOnePendingPlan() throws Exception { combined(38, 3); }

    @Test void partialCleanupAffectsOnlyItsOwnWorkspace() throws Exception {
        StagingWorkspace abandoned = StagingWorkspace.create(game);
        StagingWorkspace other = StagingWorkspace.create(game);
        Files.writeString(abandoned.directory().resolve("partial.jar.part"), "partial");
        Files.writeString(other.directory().resolve("keep.jar"), "keep");
        Path pending = PathSafety.janeDirectory(game, "pending").resolve("keep.txt");
        Files.writeString(pending, "keep");
        Path backup = PathSafety.janeDirectory(game, "backups").resolve("keep.txt");
        Files.writeString(backup, "keep");
        abandoned.discard(game);
        assertFalse(Files.exists(abandoned.directory()));
        assertTrue(Files.exists(other.directory().resolve("keep.jar")));
        assertTrue(Files.exists(pending));
        assertTrue(Files.exists(backup));
    }
}
