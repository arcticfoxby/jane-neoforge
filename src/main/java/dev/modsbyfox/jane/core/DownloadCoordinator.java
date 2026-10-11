package dev.modsbyfox.jane.core;

/** Schedules each available download route once for one sync session. */
public final class DownloadCoordinator {
    public enum Action {
        TRUSTED, TRUSTED_SERVER_FALLBACK, SERVER_ONLY_CONFIRM, SERVER_ONLY,
        COMPLETE, LOOKUP_FAILED, UNAVAILABLE, INCOMPLETE, NO_SOURCE
    }

    private final boolean trustedEnabled;
    private final boolean serverEnabled;
    private final boolean providerAvailable;
    private boolean trustedAttempted;
    private boolean fallbackAttempted;
    private boolean confirmationRequested;
    private boolean serverOnlyConfirmed;
    private boolean serverOnlyAttempted;

    public DownloadCoordinator(boolean trustedEnabled, boolean serverEnabled, boolean providerAvailable) {
        this.trustedEnabled = trustedEnabled;
        this.serverEnabled = serverEnabled;
        this.providerAvailable = providerAvailable;
    }

    public synchronized Action next(JaneSyncSession.Snapshot snapshot) {
        if (snapshot == null || snapshot.running() || snapshot.resolution() == null) return Action.INCOMPLETE;
        if (snapshot.error() != null || snapshot.localErrorCount() > 0
                || snapshot.resolution().count(ResolutionPlan.Availability.LOCAL_ERROR) > 0)
            return Action.INCOMPLETE;
        if (snapshot.canInstall()) return Action.COMPLETE;
        if (!trustedEnabled && !serverEnabled) return Action.NO_SOURCE;

        if (snapshot.hasWaiting(ResolutionPlan.TransferGroup.TRUSTED)) {
            if (trustedEnabled && !trustedAttempted) {
                trustedAttempted = true;
                return Action.TRUSTED;
            }
            if (serverEnabled && providerAvailable && !fallbackAttempted) {
                fallbackAttempted = true;
                return Action.TRUSTED_SERVER_FALLBACK;
            }
        }
        if (snapshot.hasWaiting(ResolutionPlan.TransferGroup.SERVER_ONLY)
                && serverEnabled && providerAvailable && !serverOnlyAttempted) {
            if (!confirmationRequested) {
                confirmationRequested = true;
                return Action.SERVER_ONLY_CONFIRM;
            }
            if (serverOnlyConfirmed) {
                serverOnlyAttempted = true;
                return Action.SERVER_ONLY;
            }
        }
        if (snapshot.items().stream().anyMatch(item -> selected(snapshot, item)
                && item.item().availability() == ResolutionPlan.Availability.LOOKUP_FAILED))
            return Action.LOOKUP_FAILED;
        if (serverEnabled && !providerAvailable && snapshot.items().stream().anyMatch(item ->
                selected(snapshot, item)
                && item.item().availability() != ResolutionPlan.Availability.ALREADY_PRESENT
                && item.state() != JaneSyncSession.RuntimeState.READY))
            return Action.UNAVAILABLE;
        return Action.INCOMPLETE;
    }

    public synchronized void confirmServerOnly() {
        if (!confirmationRequested || serverOnlyAttempted)
            throw new IllegalStateException("SERVER_ONLY confirmation was not requested");
        serverOnlyConfirmed = true;
    }

    private static boolean selected(JaneSyncSession.Snapshot snapshot, JaneSyncSession.ItemState item) {
        return snapshot.selectedModIds().contains(item.item().comparison().required().modId());
    }
}
