package dev.modsbyfox.jane.core;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DownloadCoordinatorTest {
    private static JaneSyncSession waitingTrusted() throws Exception {
        ManifestEntry entry = new ManifestEntry("create", "Create", "1", 10, "a".repeat(128));
        RequiredManifest manifest = new RequiredManifest(RequiredManifest.PROTOCOL, List.of(entry));
        var results = Comparison.compare(manifest, Map.of(), path -> "");
        JaneSyncSession session = new JaneSyncSession(new PendingSyncContext("example.org", manifest, results));
        session.publishResolution(ResolutionPlan.resolve(results, target -> Optional.of(
                new ResolutionPlan.Source("create.jar", URI.create("https://cdn.modrinth.com/create.jar"), 10))));
        return session;
    }

    @Test void trustedRouteRunsOnceAndFallbackNeedsProvider() throws Exception {
        JaneSyncSession session = waitingTrusted();
        DownloadCoordinator noProvider = new DownloadCoordinator(true, true, false);
        assertEquals(DownloadCoordinator.Action.TRUSTED, noProvider.next(session.snapshot()));
        assertEquals(DownloadCoordinator.Action.UNAVAILABLE, noProvider.next(session.snapshot()));

        DownloadCoordinator withProvider = new DownloadCoordinator(true, true, true);
        assertEquals(DownloadCoordinator.Action.TRUSTED, withProvider.next(session.snapshot()));
        assertEquals(DownloadCoordinator.Action.TRUSTED_SERVER_FALLBACK, withProvider.next(session.snapshot()));
        assertEquals(DownloadCoordinator.Action.INCOMPLETE, withProvider.next(session.snapshot()));
    }
}
