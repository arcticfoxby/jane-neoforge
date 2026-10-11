package dev.modsbyfox.jane.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Thread-safe state shared by the background services and read-only screens. */
public final class JaneSyncSession {
    public enum RuntimeState { WAITING, RESOLVING, DOWNLOADING, VERIFYING, READY, FAILED, LOCAL_ERROR }
    public record ItemState(ResolutionPlan.Item item, RuntimeState state, long downloadedBytes,
                            ResolutionPlan.TransferRoute completedVia) { }
    public record Snapshot(ResolutionPlan resolution, List<ItemState> items, boolean running, boolean started,
                           String error, int resolutionProcessed, int resolutionTotal,
                           ResolutionPlan.TransferGroup activeGroup, ResolutionPlan.TransferRoute activeRoute,
                           int batchTotal, long batchReadyAtStart, long batchReadyBytesAtStart,
                           Set<String> selectedModIds) {
        public Snapshot { selectedModIds = Set.copyOf(selectedModIds); }
        public int resolutionPercent() { return resolutionTotal == 0 ? 100 : resolutionProcessed * 100 / resolutionTotal; }
        public long totalBytes() {
            return items.stream().filter(i -> selected(i) && inGroup(i, activeGroup))
                    .mapToLong(i -> i.item().comparison().required().fileSize()).sum();
        }
        public long downloadedBytes() {
            return items.stream().filter(i -> selected(i) && inGroup(i, activeGroup))
                    .mapToLong(ItemState::downloadedBytes).sum();
        }
        public int progressPercent() {
            long total = totalBytes() - batchReadyBytesAtStart;
            return total == 0 ? 0 : (int) Math.max(0,
                    Math.min(100, (downloadedBytes() - batchReadyBytesAtStart) * 100 / total));
        }
        public long readyCount() { return items.stream().filter(i -> selected(i) && i.state() == RuntimeState.READY).count(); }
        public long selectedTransferTotalBytes() {
            return items.stream().filter(i -> selected(i)
                            && i.item().availability() != ResolutionPlan.Availability.ALREADY_PRESENT)
                    .mapToLong(i -> i.item().comparison().required().fileSize()).sum();
        }
        public long selectedTransferredBytes() {
            return items.stream().filter(i -> selected(i) && downloadable(i.item().availability()))
                    .mapToLong(i -> i.state() == RuntimeState.READY
                            ? i.item().comparison().required().fileSize()
                            : running && (i.state() == RuntimeState.DOWNLOADING || i.state() == RuntimeState.VERIFYING)
                            ? i.downloadedBytes() : 0).sum();
        }
        public int overallProgressPercent() {
            long total = selectedTransferTotalBytes();
            return total == 0 ? 0 : (int) Math.max(0, Math.min(100,
                    selectedTransferredBytes() * 100 / total));
        }
        public long readyCount(ResolutionPlan.Availability availability) {
            return items.stream().filter(i -> selected(i) && i.item().availability() == availability
                    && i.state() == RuntimeState.READY).count();
        }
        @Deprecated public long readyCount(ResolutionPlan.Classification classification) {
            return items.stream().filter(i -> i.item().classification() == classification && i.state() == RuntimeState.READY).count();
        }
        public long failedCount() { return items.stream().filter(i -> selected(i) && i.state() == RuntimeState.FAILED).count(); }
        public long failedCount(ResolutionPlan.Availability availability) {
            return items.stream().filter(i -> selected(i) && i.item().availability() == availability
                    && i.state() == RuntimeState.FAILED).count();
        }
        public long localErrorCount() {
            return items.stream().filter(i -> i.state() == RuntimeState.LOCAL_ERROR).count();
        }
        @Deprecated public long failedCount(ResolutionPlan.Classification classification) {
            return items.stream().filter(i -> i.item().classification() == classification && i.state() == RuntimeState.FAILED).count();
        }
        public long processedCount() {
            return Math.max(0, items.stream().filter(i -> selected(i) && inGroup(i, activeGroup)
                            && i.state() != RuntimeState.WAITING)
                    .count() - batchReadyAtStart);
        }
        public long remainingCount(ResolutionPlan.TransferGroup group) { return queue(group).size(); }
        public List<ResolutionPlan.Item> queue(ResolutionPlan.TransferGroup group) {
            return items.stream().filter(i -> selected(i) && inGroup(i, group)
                            && (i.state() == RuntimeState.WAITING || i.state() == RuntimeState.FAILED))
                    .map(ItemState::item).toList();
        }
        private boolean selected(ItemState item) {
            return selectedModIds.contains(item.item().comparison().required().modId());
        }
        private static boolean inGroup(ItemState item, ResolutionPlan.TransferGroup group) {
            return group != null && item.item().availability() == (group == ResolutionPlan.TransferGroup.TRUSTED
                    ? ResolutionPlan.Availability.TRUSTED_AVAILABLE : ResolutionPlan.Availability.SERVER_ONLY);
        }
        public boolean groupReady(ResolutionPlan.TransferGroup group) {
            return items.stream().filter(i -> selected(i) && inGroup(i, group))
                    .allMatch(i -> i.state() == RuntimeState.READY);
        }
        public boolean hasWaiting(ResolutionPlan.TransferGroup group) { return !queue(group).isEmpty(); }
        @Deprecated public boolean providerReady(ResolutionPlan.Classification classification) {
            return items.stream().filter(i -> i.item().classification() == classification)
                    .allMatch(i -> i.state() == RuntimeState.READY);
        }
        @Deprecated public boolean hasWaiting(ResolutionPlan.Classification classification) {
            return items.stream().anyMatch(i -> i.item().classification() == classification && i.state() == RuntimeState.WAITING);
        }
        @Deprecated public ResolutionPlan.Classification activeProvider() {
            return activeGroup == ResolutionPlan.TransferGroup.TRUSTED ? ResolutionPlan.Classification.MODRINTH_DOWNLOADABLE
                    : activeGroup == ResolutionPlan.TransferGroup.SERVER_ONLY ? ResolutionPlan.Classification.SERVER_DOWNLOADABLE : null;
        }
        public boolean canInstall() {
            return resolution != null && !running && error == null
                    && resolution.count(ResolutionPlan.Availability.LOCAL_ERROR) == 0
                    && localErrorCount() == 0
                    && items.stream().filter(this::selected).allMatch(i ->
                    i.item().availability() == ResolutionPlan.Availability.ALREADY_PRESENT
                            || i.state() == RuntimeState.READY);
        }
    }

    private final PendingSyncContext context;
    private boolean resolutionStarted;
    private volatile Snapshot snapshot;

    public JaneSyncSession(PendingSyncContext context) {
        this.context = context;
        Set<String> allIds = context.manifest().entries().stream().map(ManifestEntry::modId)
                .collect(Collectors.toUnmodifiableSet());
        snapshot = new Snapshot(null, List.of(), false, false, null, 0, 0,
                null, null, 0, 0, 0, allIds);
    }
    public PendingSyncContext context() { return context; }
    public Snapshot snapshot() { return snapshot; }
    public synchronized boolean resolutionStarted() { return resolutionStarted; }

    /** Choose the install queue without altering the Required-file assessment. */
    public synchronized boolean selectModIds(Set<String> selectedModIds) {
        Snapshot old = snapshot;
        if (old.running() || old.started()) return false;
        Set<String> allIds = context.manifest().entries().stream().map(ManifestEntry::modId)
                .collect(Collectors.toUnmodifiableSet());
        if (!allIds.containsAll(selectedModIds)) throw new IllegalArgumentException("Unknown selected mod");
        snapshot = new Snapshot(old.resolution(), old.items(), old.running(), old.started(), old.error(),
                old.resolutionProcessed(), old.resolutionTotal(), old.activeGroup(), old.activeRoute(),
                old.batchTotal(), old.batchReadyAtStart(), old.batchReadyBytesAtStart(), selectedModIds);
        return true;
    }

    public synchronized boolean beginResolution(int total) {
        if (resolutionStarted) return false;
        if (total < 0 || snapshot.resolution() != null) throw new IllegalArgumentException("Invalid resolution start");
        resolutionStarted = true;
        snapshot = new Snapshot(null, List.of(), false, false, null, 0, total, null, null, 0, 0, 0,
                snapshot.selectedModIds());
        return true;
    }

    /** Let a cancelled or failed source lookup be retried without carrying a partial result. */
    public synchronized boolean abortResolution() {
        Snapshot old = snapshot;
        if (!resolutionStarted || old.started() || old.running()) return false;
        resolutionStarted = false;
        snapshot = new Snapshot(null, List.of(), false, false, null, 0, 0,
                null, null, 0, 0, 0, old.selectedModIds());
        return true;
    }
    public synchronized void resolutionProgress(int processed) {
        if (snapshot.resolution() != null || processed < snapshot.resolutionProcessed()
                || processed > snapshot.resolutionTotal()) throw new IllegalArgumentException("Invalid resolution progress");
        snapshot = new Snapshot(null, List.of(), false, false, null, processed, snapshot.resolutionTotal(),
                null, null, 0, 0, 0, snapshot.selectedModIds());
    }
    public synchronized void publishResolution(ResolutionPlan resolution) {
        if (snapshot.resolution() != null) throw new IllegalStateException("Resolution already published");
        List<ItemState> states = resolution.items().stream().map(item -> new ItemState(item,
                downloadable(item.availability()) ? RuntimeState.WAITING : null, 0, null)).toList();
        snapshot = new Snapshot(resolution, states, false, false, null,
                resolution.items().size(), resolution.items().size(), null, null, 0, 0, 0,
                snapshot.selectedModIds());
    }
    public synchronized void publishRetryResolution(ResolutionPlan resolution) {
        Snapshot old = snapshot;
        if (old.running() || old.resolution() == null || old.items().size() != resolution.items().size())
            throw new IllegalStateException("Cannot replace resolution while transferring");
        List<ItemState> states = new ArrayList<>();
        for (int index = 0; index < resolution.items().size(); index++) {
            ItemState previous = old.items().get(index);
            ResolutionPlan.Item item = resolution.items().get(index);
            if (!previous.item().comparison().required().modId().equals(item.comparison().required().modId()))
                throw new IllegalArgumentException("Resolution item order changed");
            if (previous.item().availability() != ResolutionPlan.Availability.LOOKUP_FAILED
                    && !previous.item().equals(item)) throw new IllegalArgumentException("Resolved source changed");
            states.add(previous.item().availability() == ResolutionPlan.Availability.LOOKUP_FAILED
                    ? new ItemState(item, downloadable(item.availability()) ? RuntimeState.WAITING : null, 0, null)
                    : previous);
        }
        snapshot = new Snapshot(resolution, List.copyOf(states), false, old.started(), null,
                old.resolutionProcessed(), old.resolutionTotal(), old.activeGroup(), old.activeRoute(), 0, 0, 0,
                old.selectedModIds());
    }
    private static boolean downloadable(ResolutionPlan.Availability availability) {
        return availability == ResolutionPlan.Availability.TRUSTED_AVAILABLE
                || availability == ResolutionPlan.Availability.SERVER_ONLY;
    }

    public synchronized boolean startTransfer(ResolutionPlan.TransferGroup group, ResolutionPlan.TransferRoute route) {
        return startTransfer(group, route, false);
    }
    public synchronized boolean startServerOnlyConfirmed() {
        return startTransfer(ResolutionPlan.TransferGroup.SERVER_ONLY, ResolutionPlan.TransferRoute.CURRENT_SERVER, true);
    }
    private boolean startTransfer(ResolutionPlan.TransferGroup group, ResolutionPlan.TransferRoute route, boolean confirmed) {
        Snapshot old = snapshot;
        if (old.resolution() == null || old.running() || old.error() != null || group == null || route == null
                || (group == ResolutionPlan.TransferGroup.SERVER_ONLY
                    && (route != ResolutionPlan.TransferRoute.CURRENT_SERVER || !confirmed))
                || (route == ResolutionPlan.TransferRoute.CURRENT_SERVER && context.provider() == null)
                || old.queue(group).isEmpty()) return false;
        List<ItemState> reset = old.items().stream().map(i -> old.selected(i)
                && Snapshot.inGroup(i, group) && i.state() == RuntimeState.FAILED
                ? new ItemState(i.item(), RuntimeState.WAITING, 0, null) : i).toList();
        snapshot = new Snapshot(old.resolution(), reset, true, true, null, old.resolutionProcessed(), old.resolutionTotal(),
                group, route, old.queue(group).size(), old.readyCount(group == ResolutionPlan.TransferGroup.TRUSTED
                ? ResolutionPlan.Availability.TRUSTED_AVAILABLE : ResolutionPlan.Availability.SERVER_ONLY),
                old.items().stream().filter(i -> old.selected(i) && Snapshot.inGroup(i, group)
                        && i.state() == RuntimeState.READY)
                        .mapToLong(i -> i.item().comparison().required().fileSize()).sum(), old.selectedModIds());
        return true;
    }
    @Deprecated public synchronized boolean startDownloads() {
        return startTransfer(ResolutionPlan.TransferGroup.TRUSTED, ResolutionPlan.TransferRoute.TRUSTED_SOURCE);
    }
    @Deprecated public synchronized boolean startDownloads(ResolutionPlan.Classification classification) {
        return classification == ResolutionPlan.Classification.MODRINTH_DOWNLOADABLE && startDownloads();
    }

    public synchronized void update(String modId, RuntimeState state, long bytes) {
        Snapshot old = snapshot;
        if (!old.running()) throw new IllegalStateException("Download task is not running");
        List<ItemState> next = new ArrayList<>(old.items());
        for (int index = 0; index < next.size(); index++) {
            ItemState previous = next.get(index);
            if (!previous.item().comparison().required().modId().equals(modId)) continue;
            if (!Snapshot.inGroup(previous, old.activeGroup()) || previous.state() == RuntimeState.READY)
                throw new IllegalArgumentException("Wrong download item or READY item cannot be changed");
            long total = previous.item().comparison().required().fileSize();
            next.set(index, new ItemState(previous.item(), state, Math.max(0, Math.min(total, bytes)),
                    state == RuntimeState.READY ? old.activeRoute() : null));
            snapshot = new Snapshot(old.resolution(), List.copyOf(next), true, true, old.error(),
                    old.resolutionProcessed(), old.resolutionTotal(), old.activeGroup(), old.activeRoute(),
                    old.batchTotal(), old.batchReadyAtStart(), old.batchReadyBytesAtStart(), old.selectedModIds());
            return;
        }
        throw new IllegalArgumentException("Unknown required mod");
    }
    public synchronized void finishTransfer(boolean cancelled, String error) {
        Snapshot old = snapshot;
        List<ItemState> states = old.items();
        if (cancelled) states = old.items().stream().map(i -> Snapshot.inGroup(i, old.activeGroup())
                && i.state() != RuntimeState.READY && i.state() != RuntimeState.LOCAL_ERROR
                ? new ItemState(i.item(), RuntimeState.WAITING, 0, null) : i).toList();
        snapshot = new Snapshot(old.resolution(), states, false, old.started(), error,
                old.resolutionProcessed(), old.resolutionTotal(), old.activeGroup(), old.activeRoute(),
                old.batchTotal(), old.batchReadyAtStart(), old.batchReadyBytesAtStart(), old.selectedModIds());
    }
    @Deprecated public synchronized void finish(String error) { finishTransfer(false, error); }
}
