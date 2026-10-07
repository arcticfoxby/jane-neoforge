package dev.modsbyfox.jane.core;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SyncNoticeTest {
    private static final String HASH = "a".repeat(128);

    private JaneSyncSession session(boolean unresolved) throws Exception {
        ManifestEntry downloadable = new ManifestEntry("aircraft", "Aircraft", "1", 10, HASH);
        ManifestEntry manual = new ManifestEntry("manual", "Manual", "1", 10, HASH);
        RequiredManifest manifest = new RequiredManifest(RequiredManifest.PROTOCOL, unresolved ? List.of(downloadable, manual) : List.of(downloadable));
        var comparisons = Comparison.compare(manifest, Map.of(), path -> HASH);
        JaneSyncSession session = new JaneSyncSession(new PendingSyncContext("example.org", manifest, comparisons));
        session.publishResolution(ResolutionPlan.resolve(comparisons, entry -> entry.modId().equals("manual")
                ? Optional.empty() : Optional.of(new ResolutionPlan.Source("aircraft.jar",
                URI.create("https://cdn.modrinth.com/aircraft.jar"), entry.fileSize()))));
        assertTrue(session.startDownloads());
        return session;
    }

    @Test void resolvedTrustedItemsWaitForExplicitRouteSelection() throws Exception {
        ManifestEntry entry = new ManifestEntry("example", "Example", "1", 10, HASH);
        RequiredManifest manifest = new RequiredManifest(RequiredManifest.PROTOCOL, List.of(entry));
        var comparisons = Comparison.compare(manifest, Map.of(), path -> HASH);
        JaneSyncSession session = new JaneSyncSession(new PendingSyncContext("example.org", manifest, comparisons));
        session.publishResolution(ResolutionPlan.resolve(comparisons, target -> Optional.of(
                new ResolutionPlan.Source("example.jar", URI.create("https://cdn.modrinth.com/example.jar"), 10))));
        assertEquals(SyncNotice.Kind.SOURCE_SELECTION, SyncNotice.select(session.snapshot(), false));
        assertFalse(session.snapshot().running());
        assertFalse(session.snapshot().started());
    }

    @Test void failedLookupHasDistinctNoticeAndBlocksInstall() throws Exception {
        ManifestEntry entry = new ManifestEntry("example", "Example", "1", 10, HASH);
        RequiredManifest manifest = new RequiredManifest(RequiredManifest.PROTOCOL, List.of(entry));
        var comparisons = Comparison.compare(manifest, Map.of(), path -> HASH);
        JaneSyncSession session = new JaneSyncSession(new PendingSyncContext("example.org", manifest, comparisons,
                new ServerProviderOffer(25566, "a".repeat(64))));
        session.publishResolution(ResolutionPlan.resolve(comparisons, target -> { throw new java.io.IOException("timeout"); },
                true, ignored -> { }));
        assertEquals(ResolutionPlan.Availability.LOOKUP_FAILED,
                session.snapshot().resolution().items().get(0).availability());
        assertEquals(SyncNotice.Kind.LOOKUP_FAILED, SyncNotice.select(session.snapshot(), false));
        assertFalse(session.snapshot().canInstall());
    }

    @Test
    void failedDownloadAloneIsNotCalledUnresolved() throws Exception {
        JaneSyncSession session = session(false);
        session.update("aircraft", JaneSyncSession.RuntimeState.FAILED, 0);
        session.finish(null);
        assertEquals(1, session.snapshot().failedCount());
        assertEquals(ResolutionPlan.Classification.MODRINTH_DOWNLOADABLE,
                session.snapshot().items().get(0).item().classification());
        assertEquals(SyncNotice.Kind.TRUSTED_FAILED, SyncNotice.select(session.snapshot(), false));
    }

    @Test
    void manualOnlyAndMixedFailuresChooseDistinctNotices() throws Exception {
        JaneSyncSession session = session(true);
        session.update("aircraft", JaneSyncSession.RuntimeState.READY, 10);
        session.finish(null);
        assertEquals(SyncNotice.Kind.INCOMPLETE, SyncNotice.select(session.snapshot(), false));

        JaneSyncSession mixed = session(true);
        mixed.update("aircraft", JaneSyncSession.RuntimeState.FAILED, 0);
        mixed.finish(null);
        assertEquals(SyncNotice.Kind.TRUSTED_FAILED, SyncNotice.select(mixed.snapshot(), false));
    }

    @Test
    void verifiedReadyPlanCanShowConfirmation() throws Exception {
        JaneSyncSession session = session(false);
        session.update("aircraft", JaneSyncSession.RuntimeState.READY, 10);
        session.finish(null);
        assertTrue(session.snapshot().canInstall());
        assertEquals(SyncNotice.Kind.CONFIRM, SyncNotice.select(session.snapshot(), true));
        assertEquals(SyncNotice.Kind.INCOMPLETE, SyncNotice.select(session.snapshot(), false));
    }

    @Test
    void failedPublicFileIsNotCountedReadyAndBlocksServerPhase() throws Exception {
        List<ManifestEntry> entries = new ArrayList<>();
        for (int i = 0; i < 41; i++) entries.add(new ManifestEntry("mod" + i, "Mod " + i, "1", 10, HASH));
        RequiredManifest manifest = new RequiredManifest(RequiredManifest.PROTOCOL, entries);
        var comparisons = Comparison.compare(manifest, Map.of(), path -> HASH);
        JaneSyncSession session = new JaneSyncSession(new PendingSyncContext("example.org", manifest, comparisons,
                new ServerProviderOffer(25566, "a".repeat(64))));
        session.publishResolution(ResolutionPlan.resolve(comparisons, entry ->
                Integer.parseInt(entry.modId().substring(3)) < 38
                        ? Optional.of(new ResolutionPlan.Source(entry.modId() + ".jar",
                        URI.create("https://cdn.modrinth.com/test.jar"), entry.fileSize()))
                        : Optional.empty(), true, count -> { }));
        assertTrue(session.startDownloads());
        for (int i = 0; i < 37; i++) session.update("mod" + i, JaneSyncSession.RuntimeState.READY, 10);
        session.update("mod37", JaneSyncSession.RuntimeState.FAILED, 10);
        session.finish(null);
        var snapshot = session.snapshot();
        assertEquals(37, snapshot.readyCount(ResolutionPlan.Classification.MODRINTH_DOWNLOADABLE));
        assertEquals(1, snapshot.failedCount(ResolutionPlan.Classification.MODRINTH_DOWNLOADABLE));
        assertFalse(snapshot.providerReady(ResolutionPlan.Classification.MODRINTH_DOWNLOADABLE));
        assertFalse(session.startDownloads(ResolutionPlan.Classification.SERVER_DOWNLOADABLE));
        assertEquals(SyncNotice.Kind.TRUSTED_FAILED, SyncNotice.select(snapshot, false));
    }

    @Test
    void localPrepareErrorStaysOutOfRouteRetryWhileNetworkFailureCanSwitch() throws Exception {
        ManifestEntry local = new ManifestEntry("local", "Local", "1", 10, HASH);
        ManifestEntry network = new ManifestEntry("network", "Network", "1", 10, HASH);
        RequiredManifest manifest = new RequiredManifest(RequiredManifest.PROTOCOL, List.of(local, network));
        var comparisons = Comparison.compare(manifest, Map.of(), path -> HASH);
        JaneSyncSession session = new JaneSyncSession(new PendingSyncContext("example.org", manifest, comparisons,
                new ServerProviderOffer(25566, "a".repeat(64))));
        session.publishResolution(ResolutionPlan.resolve(comparisons, entry -> Optional.of(new ResolutionPlan.Source(
                entry.modId() + ".jar", URI.create("https://cdn.modrinth.com/test.jar"), entry.fileSize()))));
        assertTrue(session.startTransfer(ResolutionPlan.TransferGroup.TRUSTED, ResolutionPlan.TransferRoute.TRUSTED_SOURCE));
        session.update("local", JaneSyncSession.RuntimeState.LOCAL_ERROR, 0);
        session.update("network", JaneSyncSession.RuntimeState.FAILED, 0);
        session.finishTransfer(false, null);
        assertEquals(List.of("network"), session.snapshot().queue(ResolutionPlan.TransferGroup.TRUSTED).stream()
                .map(item -> item.comparison().required().modId()).toList());
        assertEquals(SyncNotice.Kind.RUNTIME_LOCAL_ERROR, SyncNotice.select(session.snapshot(), false));
        assertFalse(session.snapshot().canInstall());
        assertTrue(session.startTransfer(ResolutionPlan.TransferGroup.TRUSTED, ResolutionPlan.TransferRoute.CURRENT_SERVER));
        assertEquals(JaneSyncSession.RuntimeState.LOCAL_ERROR, session.snapshot().items().get(0).state());
        assertEquals(JaneSyncSession.RuntimeState.WAITING, session.snapshot().items().get(1).state());
    }
}
