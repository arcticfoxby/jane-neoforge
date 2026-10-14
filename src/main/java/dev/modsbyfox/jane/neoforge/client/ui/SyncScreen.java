package dev.modsbyfox.jane.neoforge.client.ui;

import dev.modsbyfox.jane.core.ClientCompatibilityReport;
import dev.modsbyfox.jane.core.Comparison;
import dev.modsbyfox.jane.core.DownloadCoordinator;
import dev.modsbyfox.jane.core.JaneSyncSession;
import dev.modsbyfox.jane.core.PendingSyncContext;
import dev.modsbyfox.jane.core.ResolutionPlan;
import dev.modsbyfox.jane.core.StagingWorkspace;
import dev.modsbyfox.jane.neoforge.client.JaneClientAudit;
import dev.modsbyfox.jane.core.SyncSelection;
import dev.modsbyfox.jane.core.UpdatePlan;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** NeoForge client sync flow: choose files, resolve exact sources, stage, verify, then restart. */
final class SyncScreen extends UiScreen {
    private static final Logger LOGGER = LoggerFactory.getLogger("jane");
    private final PendingSyncContext context;
    private final ClientCompatibilityReport extras;
    private boolean auditWarning;
    private final JaneClientUi.JoinActions actions;
    private final JaneSyncSession session;
    private final SyncSelection selection;
    private final Path gameDir;
    private volatile boolean resolving;
    private volatile boolean busy;
    private volatile boolean closed;
    private volatile Component notice;
    private volatile UpdatePlan ready;
    private volatile AtomicBoolean activeCancel;
    private volatile ResolutionPlan.TransferRoute switchTo;
    private boolean initialLookupStarted;
    private StagingWorkspace workspace;
    private DownloadCoordinator coordinator;
    private boolean trustedEnabled = true;
    private boolean serverEnabled = true;
    private Button primary;
    private Button select;
    private Button retry;
    private Button details;
    private Button trusted;
    private Button server;

    SyncScreen(Screen parent, PendingSyncContext context, ClientCompatibilityReport extras,
               boolean auditSaveFailed,
               JaneClientUi.JoinActions actions) {
        super(Component.translatable("jane.sync.title"), parent);
        this.context = context;
        this.extras = extras;
        this.auditWarning = auditSaveFailed;
        this.actions = actions;
        this.session = new JaneSyncSession(context);
        this.selection = new SyncSelection(context);
        this.gameDir = FMLPaths.GAMEDIR.get();
        audit("USER_SELECT_SYNC", Map.of("selectedCount",
                Integer.toString(selection.selectedUnmatchedModIds().size())));
    }

    JaneSyncSession session() { return session; }
    SyncSelection selection() { return selection; }
    boolean selectionEditable() { return !busy && !resolving && !session.snapshot().started() && ready == null; }
    boolean trustedEnabled() { return trustedEnabled; }
    boolean serverEnabled() { return serverEnabled; }
    boolean serverAvailable() { return SyncTransferService.serverRouteAvailable(session); }

    void setSourceEnabled(ResolutionPlan.TransferRoute route, boolean enabled) {
        if (!selectionEditable()) return;
        if (route == ResolutionPlan.TransferRoute.TRUSTED_SOURCE) trustedEnabled = enabled;
        else if (route == ResolutionPlan.TransferRoute.CURRENT_SERVER && serverAvailable()) serverEnabled = enabled;
        coordinator = null;
        audit("DOWNLOAD_SOURCE_SETTING", Map.of("reason", route.name(), "status", Boolean.toString(enabled)));
    }

    void selectionChanged(String modId, boolean selected) {
        if (selectionEditable()) session.selectModIds(selection.selectedModIds());
        coordinator = null;
        audit(selected ? "USER_SELECT_OPTIONAL" : "USER_DESELECT_OPTIONAL", Map.of(
                "modId", modId, "selectedCount", Integer.toString(selection.selectedUnmatchedModIds().size())));
    }

    @Override protected void init() {
        clearWidgets();
        boolean compact = height < 180;
        int w = buttonWidth();
        int x = (width - w) / 2;
        int half = Math.max(1, (w - 4) / 2);
        int third = Math.max(1, (w - 8) / 3);
        if (!compact) {
            trusted = addRenderableWidget(Button.builder(Component.translatable("jane.sync.quick_trusted"),
                    button -> quickRoute(ResolutionPlan.TransferRoute.TRUSTED_SOURCE))
                    .bounds(x, height - 102, half, 20).build());
            server = addRenderableWidget(Button.builder(Component.translatable("jane.sync.quick_server"),
                    button -> quickRoute(ResolutionPlan.TransferRoute.CURRENT_SERVER))
                    .bounds(x + half + 4, height - 102, half, 20).build());
            select = addRenderableWidget(Button.builder(Component.translatable("jane.sync.select_short"),
                    button -> minecraft.setScreen(new SyncSelectionScreen(this)))
                    .bounds(x, height - 78, third, 20).build());
            retry = addRenderableWidget(Button.builder(Component.translatable("jane.sync.retry_short"),
                    button -> retryLookup()).bounds(x + third + 4, height - 78, third, 20).build());
            details = addRenderableWidget(Button.builder(Component.translatable("jane.sync.details"),
                    button -> openDetails()).bounds(x + 2 * (third + 4), height - 78, third, 20).build());
            primary = addRenderableWidget(Button.builder(Component.translatable("jane.sync.download_needed"),
                    button -> primary()).bounds(x, height - 54, w, 20).build());
            addRenderableWidget(Button.builder(Component.translatable("gui.back"),
                    button -> onClose()).bounds(x, height - 30, w, 20).build());
        } else {
            trusted = addRenderableWidget(Button.builder(Component.translatable("jane.sync.quick_trusted"),
                    button -> quickRoute(ResolutionPlan.TransferRoute.TRUSTED_SOURCE))
                    .bounds(x, height - 78, half, 20).build());
            server = addRenderableWidget(Button.builder(Component.translatable("jane.sync.quick_server"),
                    button -> quickRoute(ResolutionPlan.TransferRoute.CURRENT_SERVER))
                    .bounds(x + half + 4, height - 78, half, 20).build());
            retry = addRenderableWidget(Button.builder(Component.translatable("jane.sync.retry_lookup"),
                    button -> retryLookup()).bounds(x, height - 78, w, 20).build());
            primary = addRenderableWidget(Button.builder(Component.translatable("jane.sync.download_needed"),
                    button -> primary()).bounds(x, height - 53, w, 20).build());
            select = addRenderableWidget(Button.builder(Component.translatable("jane.sync.select_short"),
                    button -> minecraft.setScreen(new SyncSelectionScreen(this)))
                    .bounds(x, height - 29, third, 20).build());
            details = addRenderableWidget(Button.builder(Component.translatable("jane.sync.details"),
                    button -> openDetails()).bounds(x + third + 4, height - 29, third, 20).build());
            addRenderableWidget(Button.builder(Component.translatable("gui.back"),
                    button -> onClose()).bounds(x + 2 * (third + 4), height - 29, third, 20).build());
        }
        refreshButtons();
        if (!initialLookupStarted) {
            initialLookupStarted = true;
            startResolution();
        }
    }

    private void refreshButtons() {
        if (primary == null) return;
        JaneSyncSession.Snapshot snapshot = session.snapshot();
        if (ready != null) primary.setMessage(Component.translatable("jane.sync.finish"));
        else if (busy || resolving) primary.setMessage(Component.translatable("jane.sync.cancel_transfer"));
        else if (snapshot.resolution() == null) primary.setMessage(Component.translatable(
                session.resolutionStarted() ? "jane.sync.resolving_button" : "jane.sync.retry_lookup"));
        else if (unselectedUnmet() > 0 && selectedUnmet() == 0)
            primary.setMessage(Component.translatable("jane.select.try_join"));
        else if (snapshot.canInstall()) primary.setMessage(Component.translatable("jane.sync.prepare_retry"));
        else if (tinyLookupRetry(snapshot)) primary.setMessage(Component.translatable("jane.sync.retry_lookup"));
        else primary.setMessage(Component.translatable("jane.sync.download_needed"));
        primary.active = ready != null || busy || resolving || snapshot.resolution() != null
                || !session.resolutionStarted() && !closed;
        if (select != null) select.active = snapshot.resolution() != null && !busy && !resolving;
        if (details != null) details.active = snapshot.resolution() != null;
        if (retry != null) retry.active = snapshot.resolution() != null && !busy && !resolving
                && snapshot.resolution().count(ResolutionPlan.Availability.LOOKUP_FAILED) > 0;
        boolean switchingNow = busy && (switchTo != null
                || activeCancel != null && activeCancel.get());
        if (height < 180) {
            boolean switching = height >= 125 && busy
                    && snapshot.activeGroup() == ResolutionPlan.TransferGroup.TRUSTED;
            trusted.visible = switching;
            server.visible = switching;
            retry.visible = height >= 125 && !switching && retry.active;
        }
        if (trusted != null) trusted.active = snapshot.resolution() != null && trustedEnabled
                && !switchingNow
                && (snapshot.hasWaiting(ResolutionPlan.TransferGroup.TRUSTED)
                || snapshot.running() && snapshot.activeGroup() == ResolutionPlan.TransferGroup.TRUSTED)
                && !(busy && snapshot.activeRoute() == ResolutionPlan.TransferRoute.TRUSTED_SOURCE);
        if (server != null) server.active = snapshot.resolution() != null && serverEnabled && serverAvailable()
                && !switchingNow
                && (snapshot.hasWaiting(ResolutionPlan.TransferGroup.TRUSTED)
                || snapshot.hasWaiting(ResolutionPlan.TransferGroup.SERVER_ONLY)
                || snapshot.running() && snapshot.activeGroup() == ResolutionPlan.TransferGroup.TRUSTED)
                && !(busy && snapshot.activeRoute() == ResolutionPlan.TransferRoute.CURRENT_SERVER);
        if (trusted != null) trusted.setMessage(routeLabel("jane.sync.quick_trusted",
                busy && snapshot.activeRoute() == ResolutionPlan.TransferRoute.TRUSTED_SOURCE));
        if (server != null) server.setMessage(routeLabel("jane.sync.quick_server",
                busy && snapshot.activeRoute() == ResolutionPlan.TransferRoute.CURRENT_SERVER));
    }

    private static Component routeLabel(String key, boolean selected) {
        Component label = Component.translatable(key);
        return selected ? Component.literal("✓ ").append(label) : label;
    }

    private long selectedUnmet() {
        return context.results().stream().filter(result -> result.status() != Comparison.Status.OK
                && selection.isSelected(result.required().modId())).count();
    }

    private void openDetails() {
        if (session.snapshot().resolution() == null) return;
        audit("USER_VIEW_SYNC_DETAILS", Map.of());
        minecraft.setScreen(new SyncDetailsScreen(this, session));
    }

    private boolean tinyLookupRetry(JaneSyncSession.Snapshot snapshot) {
        return height < 125 && snapshot.resolution() != null
                && snapshot.resolution().count(ResolutionPlan.Availability.LOOKUP_FAILED) > 0
                && !snapshot.hasWaiting(ResolutionPlan.TransferGroup.TRUSTED)
                && !snapshot.hasWaiting(ResolutionPlan.TransferGroup.SERVER_ONLY);
    }

    private long unselectedUnmet() {
        return context.results().stream().filter(result -> result.status() != Comparison.Status.OK
                && !selection.isSelected(result.required().modId())).count();
    }

    private void startResolution() {
        if (busy || resolving || closed || session.resolutionStarted()) return;
        audit("SOURCE_LOOKUP_STARTED", Map.of("selectedCount",
                Integer.toString(selection.selectedUnmatchedModIds().size())));
        resolving = true;
        AtomicBoolean cancelled = new AtomicBoolean();
        activeCancel = cancelled;
        CompletableFuture.runAsync(() -> {
            try { SyncTransferService.resolve(session, selection, cancelled::get); }
            catch (Exception exception) { throw new java.util.concurrent.CompletionException(exception); }
        }).whenComplete((unused, failure) -> minecraft.execute(() -> {
            if (failure != null || cancelled.get()) {
                session.abortResolution();
                audit(cancelled.get() ? "SOURCE_LOOKUP_CANCELLED" : "SOURCE_LOOKUP_FAILED", Map.of());
                notice = cancelled.get() ? Component.translatable("jane.sync.download_paused")
                        : Component.translatable("jane.sync.failed");
            } else {
                audit("SOURCE_LOOKUP_COMPLETED", Map.of());
                notice = null;
            }
            resolving = false;
            activeCancel = null;
            if (minecraft.screen == this) refreshButtons();
        }));
    }

    private void retryLookup() {
        if (busy || resolving || ready != null || session.snapshot().resolution() == null) return;
        audit("USER_RETRY_LOOKUP", Map.of("selectedCount",
                Integer.toString(selection.selectedUnmatchedModIds().size())));
        resolving = true;
        AtomicBoolean cancelled = new AtomicBoolean();
        activeCancel = cancelled;
        CompletableFuture.runAsync(() -> {
            try { SyncTransferService.retryLookupFailures(session, selection, cancelled::get); }
            catch (Exception exception) { throw new java.util.concurrent.CompletionException(exception); }
        }).whenComplete((unused, failure) -> minecraft.execute(() -> {
            resolving = false;
            activeCancel = null;
            if (cancelled.get()) {
                audit("SOURCE_LOOKUP_CANCELLED", Map.of("reason", "RETRY"));
                notice = Component.translatable("jane.sync.download_paused");
            } else if (failure != null) {
                audit("SOURCE_LOOKUP_FAILED", Map.of("reason", "RETRY"));
                notice = Component.translatable("jane.sync.failed");
            } else {
                audit("SOURCE_LOOKUP_COMPLETED", Map.of("reason", "RETRY"));
                notice = null;
            }
            if (minecraft.screen == this) refreshButtons();
        }));
    }

    private void primary() {
        if (ready != null) { launchUpdater(); return; }
        if (busy || resolving) {
            AtomicBoolean token = activeCancel;
            if (token != null) token.set(true);
            if (busy) {
                ResolutionPlan.TransferRoute route = session.snapshot().activeRoute();
                audit("DOWNLOAD_PAUSE_REQUESTED", Map.of("reason",
                        route == null ? "PREPARATION" : route.name()));
            }
            notice = Component.translatable("jane.sync.quick_stopping");
            return;
        }
        if (session.snapshot().resolution() == null) {
            startResolution();
            return;
        }
        if (selectedUnmet() == 0 && unselectedUnmet() > 0) {
            audit("USER_SELECT_DIRECT_JOIN", Map.of("skippedRequired", Long.toString(unselectedUnmet())));
            minecraft.setScreen(new JoinRiskScreen(this, context, extras, selection, auditWarning, actions));
            return;
        }
        if (session.snapshot().canInstall()) {
            retryPreparation();
            return;
        }
        if (tinyLookupRetry(session.snapshot())) {
            retryLookup();
            return;
        }
        if (!session.snapshot().selectedModIds().equals(selection.selectedModIds())
                && !session.selectModIds(selection.selectedModIds())) {
            notice = Component.translatable("jane.risk.selection_changed");
            return;
        }
        coordinator = new DownloadCoordinator(trustedEnabled, serverEnabled, serverAvailable());
        audit("DOWNLOAD_CHOICE", Map.of("downloadCount", Long.toString(selectedUnmet()),
                "selectedCount", Integer.toString(selection.selectedUnmatchedModIds().size())));
        continueDownload();
    }

    private void continueDownload() {
        if (coordinator == null || busy || resolving || ready != null) return;
        DownloadCoordinator.Action action = coordinator.next(session.snapshot());
        switch (action) {
            case TRUSTED -> beginTransfer(ResolutionPlan.TransferGroup.TRUSTED,
                    ResolutionPlan.TransferRoute.TRUSTED_SOURCE, false);
            case TRUSTED_SERVER_FALLBACK -> beginTransfer(ResolutionPlan.TransferGroup.TRUSTED,
                    ResolutionPlan.TransferRoute.CURRENT_SERVER, false);
            case SERVER_ONLY_CONFIRM -> minecraft.setScreen(new ServerSourceConfirmScreen(this,
                    session.snapshot().remainingCount(ResolutionPlan.TransferGroup.SERVER_ONLY),
                    serverOnlyRemainingBytes()));
            case SERVER_ONLY -> beginTransfer(ResolutionPlan.TransferGroup.SERVER_ONLY,
                    ResolutionPlan.TransferRoute.CURRENT_SERVER, true);
            case COMPLETE -> {
                if (ready == null) notice = Component.translatable("jane.sync.selected_completed");
            }
            case LOOKUP_FAILED -> notice = Component.translatable("jane.sync.lookup_failed");
            case UNAVAILABLE -> notice = Component.translatable("jane.sync.server_source_unavailable");
            case NO_SOURCE -> notice = Component.translatable("jane.sync.no_source");
            case INCOMPLETE -> notice = Component.translatable("jane.sync.incomplete");
        }
        refreshButtons();
    }

    void confirmServerOnly() {
        audit("SERVER_ONLY_CONFIRMED", Map.of("selectedCount",
                Integer.toString(selection.selectedUnmatchedModIds().size())));
        if (coordinator != null) coordinator.confirmServerOnly();
        minecraft.setScreen(this);
        if (coordinator != null) continueDownload();
        else beginTransfer(ResolutionPlan.TransferGroup.SERVER_ONLY,
                ResolutionPlan.TransferRoute.CURRENT_SERVER, true);
    }

    void declineServerOnly() {
        audit("SERVER_ONLY_DECLINED", Map.of("selectedCount",
                Integer.toString(selection.selectedUnmatchedModIds().size())));
        coordinator = null;
        notice = Component.translatable("jane.sync.download_paused");
    }

    private void quickRoute(ResolutionPlan.TransferRoute route) {
        if (session.snapshot().resolution() == null || ready != null) return;
        if (route == ResolutionPlan.TransferRoute.CURRENT_SERVER && (!serverAvailable() || !serverEnabled)) return;
        if (route == ResolutionPlan.TransferRoute.TRUSTED_SOURCE && !trustedEnabled) return;
        if (busy) {
            if (route == session.snapshot().activeRoute()) return;
            audit("DOWNLOAD_ROUTE_SWITCH", Map.of("reason", route.name()));
            switchTo = route;
            AtomicBoolean token = activeCancel;
            if (token != null) token.set(true);
            notice = Component.translatable("jane.sync.quick_switching");
            return;
        }
        coordinator = null;
        audit("DOWNLOAD_ROUTE_SELECTED", Map.of("reason", route.name()));
        if (session.snapshot().hasWaiting(ResolutionPlan.TransferGroup.TRUSTED))
            beginTransfer(ResolutionPlan.TransferGroup.TRUSTED, route, false);
        else if (route == ResolutionPlan.TransferRoute.CURRENT_SERVER
                && session.snapshot().hasWaiting(ResolutionPlan.TransferGroup.SERVER_ONLY))
            minecraft.setScreen(new ServerSourceConfirmScreen(this,
                    session.snapshot().remainingCount(ResolutionPlan.TransferGroup.SERVER_ONLY),
                    serverOnlyRemainingBytes()));
    }

    private long serverOnlyRemainingBytes() {
        return session.snapshot().queue(ResolutionPlan.TransferGroup.SERVER_ONLY).stream()
                .mapToLong(item -> item.comparison().required().fileSize()).sum();
    }

    private void beginTransfer(ResolutionPlan.TransferGroup group, ResolutionPlan.TransferRoute route,
                               boolean serverOnlyConfirmed) {
        if (busy || resolving || ready != null) return;
        try {
            if (workspace == null) workspace = StagingWorkspace.create(gameDir);
        } catch (IOException exception) {
            notice = Component.translatable("jane.sync.local_runtime_error");
            return;
        }
        boolean started = serverOnlyConfirmed ? session.startServerOnlyConfirmed() : session.startTransfer(group, route);
        if (!started) {
            notice = Component.translatable("jane.sync.incomplete");
            return;
        }
        audit("DOWNLOAD_ROUTE_STARTED", Map.of("reason", route.name(), "status", group.name()));
        selection.freezeChoices();
        busy = true;
        notice = null;
        AtomicBoolean cancelled = new AtomicBoolean();
        activeCancel = cancelled;
        StagingWorkspace activeWorkspace = workspace;
        CompletableFuture.supplyAsync(() -> {
            try {
                return SyncTransferService.transfer(session, selection, gameDir, activeWorkspace,
                        group, route, cancelled::get);
            } catch (Exception exception) {
                throw new java.util.concurrent.CompletionException(exception);
            }
        }).whenComplete((plan, failure) -> minecraft.execute(() -> {
            busy = false;
            activeCancel = null;
            if (plan != null) ready = plan;
            if (failure != null && !cancelled.get()) notice = Component.translatable("jane.sync.failed");
            else if (cancelled.get()) {
                audit("DOWNLOAD_PAUSED", Map.of("reason", route.name()));
                notice = Component.translatable("jane.sync.download_paused");
            }
            if (closed) {
                discardWorkspace();
                return;
            }
            ResolutionPlan.TransferRoute requested = switchTo;
            switchTo = null;
            if (requested != null && ready == null) {
                coordinator = null;
                if (session.snapshot().hasWaiting(group)) {
                    audit("DOWNLOAD_ROUTE_SWITCH_COMPLETE", Map.of("reason", requested.name()));
                    beginTransfer(group, requested, false);
                } else if (!continueServerOnlyAfterQuickRoute()) {
                    notice = Component.translatable("jane.sync.quick_no_remaining");
                }
            } else if (failure == null && !cancelled.get() && plan == null && coordinator != null) {
                continueDownload();
            } else if (failure == null && !cancelled.get() && plan == null && coordinator == null) {
                continueServerOnlyAfterQuickRoute();
            }
            if (minecraft.screen == this) refreshButtons();
        }));
        refreshButtons();
    }

    private boolean continueServerOnlyAfterQuickRoute() {
        if (ready != null || session.snapshot().hasWaiting(ResolutionPlan.TransferGroup.TRUSTED)
                || !session.snapshot().hasWaiting(ResolutionPlan.TransferGroup.SERVER_ONLY)
                || !serverEnabled || !serverAvailable()) return false;
        minecraft.setScreen(new ServerSourceConfirmScreen(this,
                session.snapshot().remainingCount(ResolutionPlan.TransferGroup.SERVER_ONLY),
                serverOnlyRemainingBytes()));
        return true;
    }

    /** A failed pending-plan write can be retried without downloading verified files again. */
    private void retryPreparation() {
        StagingWorkspace activeWorkspace = workspace;
        if (activeWorkspace == null || busy || ready != null) {
            notice = Component.translatable("jane.sync.local_runtime_error");
            return;
        }
        audit("UPDATE_PREPARATION_RETRIED", Map.of());
        busy = true;
        notice = null;
        AtomicBoolean cancelled = new AtomicBoolean();
        activeCancel = cancelled;
        CompletableFuture.supplyAsync(() -> {
            try {
                return activeWorkspace.prepareIfComplete(gameDir, session, cancelled::get,
                        selection.selectedModIds());
            } catch (IOException exception) {
                throw new java.util.concurrent.CompletionException(exception);
            }
        }).whenComplete((plan, failure) -> minecraft.execute(() -> {
            busy = false;
            activeCancel = null;
            if (plan != null) ready = plan;
            if (failure != null && !cancelled.get())
                notice = Component.translatable("jane.sync.failed");
            else if (cancelled.get() && plan == null)
                notice = Component.translatable("jane.sync.download_paused");
            if (closed) discardWorkspace();
            if (minecraft.screen == this) refreshButtons();
        }));
        refreshButtons();
    }

    private void launchUpdater() {
        if (busy || ready == null) return;
        audit("UPDATE_INSTALL_CONFIRMED", Map.of("downloadCount",
                Integer.toString(ready.operations().size())));
        busy = true;
        UpdatePlan plan = ready;
        CompletableFuture.runAsync(() -> {
            try { dev.modsbyfox.jane.core.PendingRecovery.verifyForLaunch(gameDir, plan); }
            catch (Exception exception) { throw new java.util.concurrent.CompletionException(exception); }
        }).whenComplete((unused, failure) -> minecraft.execute(() -> {
            if (failure != null) notice = Component.translatable("jane.recovery.unsafe");
            else if (minecraft.screen == this) {
                try {
                    UpdateLauncher.launch(minecraft, gameDir, plan);
                    return;
                } catch (IOException exception) {
                    notice = Component.translatable("jane.sync.launch_failed");
                }
            }
            busy = false;
            if (minecraft.screen == this) refreshButtons();
        }));
    }

    private void discardWorkspace() {
        StagingWorkspace old = workspace;
        if (old == null || ready != null) return;
        workspace = null;
        CompletableFuture.runAsync(() -> {
            try { old.discard(gameDir); } catch (IOException ignored) { }
        });
    }

    @Override public void onClose() {
        audit("USER_CANCEL_SYNC", Map.of("selectedCount",
                Integer.toString(selection.selectedUnmatchedModIds().size())));
        closed = true;
        AtomicBoolean token = activeCancel;
        if (token != null) token.set(true);
        if (!busy && !resolving) discardWorkspace();
        super.onClose();
    }

    private boolean audit(String event, Map<String, String> fields) {
        Map<String, String> values = new HashMap<>(fields);
        values.put("serverId", context.serverId());
        values.put("manifestDigest", selection.manifestDigest());
        try {
            JaneClientAudit.record(gameDir, event, values);
            LOGGER.info("Jane sync decision {} for serverId={}", event,
                    context.serverId().substring(0, Math.min(12, context.serverId().length())));
            return true;
        } catch (IOException | SecurityException exception) {
            auditWarning = true;
            LOGGER.warn("Jane sync decision log failed for {}: {}", event,
                    exception.getClass().getSimpleName());
            return false;
        }
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        refreshButtons();
        centered(graphics, title, 6, 0xFFFFFF);
        JaneSyncSession.Snapshot snapshot = session.snapshot();
        if (resolving || snapshot.resolution() == null) {
            if (height >= 130 || notice == null && !auditWarning)
                centered(graphics, Component.translatable("jane.sync.resolving_progress",
                        snapshot.resolutionProcessed(), snapshot.resolutionTotal(), snapshot.resolutionPercent()),
                        23, 0xDDDDDD);
        } else {
            ResolutionPlan plan = snapshot.resolution();
            if (height >= 130 || notice == null && !auditWarning)
                centered(graphics, Component.translatable("jane.sync.source_counts_compact",
                        plan.count(ResolutionPlan.Availability.TRUSTED_AVAILABLE),
                        plan.count(ResolutionPlan.Availability.SERVER_ONLY),
                        plan.count(ResolutionPlan.Availability.LOOKUP_FAILED)), 22, 0xCCCCCC);
            if (height >= 150 || notice == null && !auditWarning)
                centered(graphics, Component.translatable("jane.select.summary", selectedUnmet(), unselectedUnmet()),
                        35, 0xFFCC77);
            if (height >= 165)
                centered(graphics, Component.translatable("jane.sync.route_progress",
                        snapshot.activeRoute() == ResolutionPlan.TransferRoute.CURRENT_SERVER
                                ? Component.translatable("jane.sync.source_server")
                                : Component.translatable("jane.sync.source_trusted"),
                        snapshot.overallProgressPercent()), 48, 0xAAFFAA);
            if (height >= 210 && trusted != null && busy) {
                int hintY = height - 116;
                boolean alternative = snapshot.activeGroup() == ResolutionPlan.TransferGroup.TRUSTED
                        && (snapshot.activeRoute() == ResolutionPlan.TransferRoute.TRUSTED_SOURCE
                                && serverAvailable() && serverEnabled
                        || snapshot.activeRoute() == ResolutionPlan.TransferRoute.CURRENT_SERVER && trustedEnabled);
                centered(graphics, Component.translatable(alternative
                        ? "jane.sync.quick_slow_switch" : "jane.sync.quick_slow_network"), hintY, 0xAAAAAA);
                if (alternative)
                    centered(graphics, Component.translatable("jane.sync.quick_slow_both"), hintY + 11, 0xAAAAAA);
            }
        }
        Component shownNotice = notice != null ? notice : auditWarning
                ? Component.translatable("jane.select.audit_incomplete") : null;
        if (shownNotice != null) centered(graphics, shownNotice,
                height < 150 ? Math.max(24, height - 90) : height < 180 ? 60 : 64, 0xFFCC77);
        renderWidgets(graphics, mouseX, mouseY, partialTick);
    }
}
