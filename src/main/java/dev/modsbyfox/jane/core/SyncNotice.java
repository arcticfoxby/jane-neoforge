package dev.modsbyfox.jane.core;

/** Selects the message from availability and verified transfer state. */
public final class SyncNotice {
    public enum Kind { SOURCE_SELECTION, LOOKUP_FAILED, LOCAL_ERROR, RUNTIME_LOCAL_ERROR, TRUSTED_PARTIAL, TRUSTED_FAILED,
        SERVER_ONLY_REMAINING, SERVER_ONLY_FAILED, CONFIRM, INCOMPLETE }
    private SyncNotice() { }

    public static Kind select(JaneSyncSession.Snapshot snapshot, boolean pendingReady) {
        if (pendingReady) return Kind.CONFIRM;
        ResolutionPlan plan = snapshot.resolution();
        if (plan == null) return Kind.INCOMPLETE;
        if (plan.count(ResolutionPlan.Availability.LOCAL_ERROR) > 0) return Kind.LOCAL_ERROR;
        if (snapshot.localErrorCount() > 0) return Kind.RUNTIME_LOCAL_ERROR;
        if (snapshot.failedCount(ResolutionPlan.Availability.TRUSTED_AVAILABLE) > 0) return Kind.TRUSTED_FAILED;
        if (!snapshot.groupReady(ResolutionPlan.TransferGroup.TRUSTED)) {
            return snapshot.readyCount(ResolutionPlan.Availability.TRUSTED_AVAILABLE) > 0
                    ? Kind.TRUSTED_PARTIAL : Kind.SOURCE_SELECTION;
        }
        if (snapshot.failedCount(ResolutionPlan.Availability.SERVER_ONLY) > 0) return Kind.SERVER_ONLY_FAILED;
        if (!snapshot.groupReady(ResolutionPlan.TransferGroup.SERVER_ONLY)) return Kind.SERVER_ONLY_REMAINING;
        if (plan.items().stream().anyMatch(item -> item.availability() == ResolutionPlan.Availability.LOOKUP_FAILED
                && snapshot.selectedModIds().contains(item.comparison().required().modId())))
            return Kind.LOOKUP_FAILED;
        return Kind.INCOMPLETE;
    }
}
