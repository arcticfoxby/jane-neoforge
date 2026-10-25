package dev.modsbyfox.jane.neoforge.client.ui;

import dev.modsbyfox.jane.core.ClientCompatibilityReport;
import dev.modsbyfox.jane.core.ClientCleanupGraph;
import dev.modsbyfox.jane.core.ClientCleanupSelection;
import dev.modsbyfox.jane.core.ClientDisablePlan;
import dev.modsbyfox.jane.core.OneShotJoinOverride;
import dev.modsbyfox.jane.core.PendingRecovery;
import dev.modsbyfox.jane.core.PendingStore;
import dev.modsbyfox.jane.core.PendingSyncContext;
import dev.modsbyfox.jane.core.UpdatePlan;
import dev.modsbyfox.jane.neoforge.client.JaneClientAudit;
import dev.modsbyfox.jane.neoforge.client.NeoForgeClientCompatibility;
import dev.modsbyfox.jane.neoforge.NeoForgePhysicalDiscovery;
import dev.modsbyfox.jane.core.SyncSelection;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Extra client mods are reviewed locally after every server Required file matches. */
final class CompatibilityReviewScreen extends UiScreen {
    private static final Logger LOGGER = LoggerFactory.getLogger("jane");
    private record Row(Component heading, ClientCompatibilityReport.ExtraMod mod, int height) { }

    private final PendingSyncContext context;
    private final ClientCompatibilityReport extras;
    private final ClientCleanupSelection cleanupSelection;
    private boolean auditWarning;
    private final JaneClientUi.JoinActions actions;
    private final List<Row> rows;
    private int scroll;
    private int declarationScroll;
    private boolean busy;
    private Component notice;
    private Button disableButton;
    private Button directButton;

    CompatibilityReviewScreen(Screen parent, PendingSyncContext context, ClientCompatibilityReport extras,
                              ClientCleanupSelection cleanupSelection,
                              boolean auditSaveFailed, JaneClientUi.JoinActions actions) {
        super(Component.translatable("jane.compat.title"), parent);
        this.context = context;
        this.extras = extras;
        this.cleanupSelection = cleanupSelection;
        this.auditWarning = auditSaveFailed;
        this.actions = actions;
        List<Row> all = new ArrayList<>();
        addGroup(all, "jane.compat.client_group", extras.explicitClientMods());
        addGroup(all, "jane.compat.server_group", extras.declaredServerOnlyMods());
        addGroup(all, "jane.compat.other_group", extras.unclassifiedExtraMods());
        rows = List.copyOf(all);
    }

    private static void addGroup(List<Row> rows, String headingKey,
                                 List<ClientCompatibilityReport.ExtraMod> mods) {
        if (mods.isEmpty()) return;
        rows.add(new Row(Component.translatable(headingKey, mods.size()), null, 24));
        for (ClientCompatibilityReport.ExtraMod mod : mods)
            rows.add(new Row(null, mod, 51));
    }

    @Override protected void init() {
        clearWidgets();
        int w = buttonWidth();
        int x = (width - w) / 2;
        boolean compact = height < 190;
        int half = (w - 4) / 2;
        disableButton = addRenderableWidget(Button.builder(Component.translatable(compact
                ? "jane.compat.disable_short" : "jane.compat.disable"),
                button -> openFinalConfirmation())
                .bounds(x, height - (compact ? 52 : 76), compact ? half : w, 20).build());
        directButton = addRenderableWidget(Button.builder(Component.translatable(compact
                ? "jane.compat.direct_short" : "jane.compat.direct_join"),
                button -> directJoin())
                .bounds(compact ? x + half + 4 : x, height - 52, compact ? half : w, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), button -> onClose())
                .bounds(x, height - 28, w, 20).build());
    }

    private boolean compact() { return height < 190; }
    private int declarationTop() { return height < 140 ? 30 : compact() ? 32 : 38; }
    private int declarationBottom() { return height < 140 ? 30 : compact() ? 53 : 91; }
    private int listTop() { return height < 140 ? 32 : compact() ? 56 : 96; }
    private int listBottom() {
        return height - (height < 140 ? notice == null && !auditWarning ? 57 : 70
                : compact() ? notice == null && !auditWarning ? 58 : 80 : 83);
    }

    private void directJoin() {
        int extraCount = extras.explicitClientMods().size() + extras.otherExtraMods().size();
        audit("USER_SELECT_DIRECT_JOIN", Map.of("extraClientMods", Integer.toString(extraCount)));
        minecraft.setScreen(new JoinRiskScreen(this, context, extras,
                new SyncSelection(context), auditWarning, actions));
    }

    private List<FormattedCharSequence> declaration() {
        List<FormattedCharSequence> lines = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            lines.addAll(font.split(Component.translatable("jane.compat.boundary" + i), Math.max(90, width - 38)));
            lines.add(null);
        }
        return lines;
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        disableButton.active = !busy && !cleanupSelection.selectedExtras().isEmpty()
                && cleanupSelection.selectedServerDecisionsSafe();
        directButton.active = !busy;
        centered(graphics, title, 7, 0xFFFFFF);
        centered(graphics, Component.translatable("jane.compat.required_pass"), compact() ? 20 : 23, 0xAAFFAA);
        int declarationTop = declarationTop();
        int declarationBottom = declarationBottom();
        List<FormattedCharSequence> text = declaration();
        declarationScroll = Math.min(declarationScroll,
                Math.max(0, text.size() * 10 - (declarationBottom - declarationTop)));
        if (declarationBottom > declarationTop) {
            graphics.fill(10, declarationTop, width - 10, declarationBottom, 0x88000000);
            graphics.enableScissor(12, declarationTop, width - 12, declarationBottom);
            int y = declarationTop - declarationScroll;
            for (FormattedCharSequence line : text) {
                if (line != null && y + 10 > declarationTop && y < declarationBottom)
                    graphics.drawString(font, line, 16, y, 0xBBBBBB);
                y += 10;
            }
            graphics.disableScissor();
        }
        int top = listTop();
        int bottom = listBottom();
        scroll = Math.min(scroll, Math.max(0, totalHeight() - Math.max(0, bottom - top)));
        if (bottom > top) {
            graphics.enableScissor(10, top, width - 10, bottom);
            int rowY = top - scroll;
            for (Row row : rows) {
                if (rowY + row.height() > top && rowY < bottom)
                    drawRow(graphics, row, rowY, mouseX, mouseY);
                rowY += row.height();
            }
            graphics.disableScissor();
        }
        Component shownNotice = notice != null ? notice : auditWarning
                ? Component.translatable("jane.select.audit_incomplete") : null;
        if (shownNotice != null)
            centered(graphics, shownNotice, height < 140 ? height - 63
                    : compact() ? height - 75 : height - 89, 0xFF7777);
        renderWidgets(graphics, mouseX, mouseY, partialTick);
    }

    private int totalHeight() { return rows.stream().mapToInt(Row::height).sum(); }

    private boolean selectable(ClientCompatibilityReport.ExtraMod mod) {
        if (!mod.verified()) return false;
        if (mod.kind() == ClientCompatibilityReport.Kind.EXPLICIT_CLIENT) return true;
        if (mod.kind() != ClientCompatibilityReport.Kind.EXPLICIT_SERVER) return false;
        ClientCleanupGraph.Decision decision = cleanupSelection.serverDecision(mod.filename());
        return cleanupSelection.isSelected(mod.filename()) || decision != null && decision.eligible();
    }

    private void drawRow(GuiGraphics graphics, Row row, int y, int mouseX, int mouseY) {
        if (row.heading() != null) {
            graphics.fill(10, y, width - 10, y + row.height() - 2, 0xAA223344);
            graphics.drawString(font, fit(row.heading(), width - 32), 16, y + 7, 0x66DDFF);
            return;
        }
        ClientCompatibilityReport.ExtraMod mod = row.mod();
        boolean canToggle = selectable(mod);
        boolean selected = cleanupSelection.isSelected(mod.filename());
        graphics.fill(10, y, width - 10, y + row.height() - 2,
                mouseX >= 10 && mouseX < width - 10 && mouseY >= y && mouseY < y + row.height()
                        ? 0xAA444444 : 0x88000000);
        int left = mod.kind() == ClientCompatibilityReport.Kind.OTHER_EXTRA ? 16 : 33;
        if (left == 33) graphics.drawString(font, selected ? "☑" : "☐", 16, y + 16,
                canToggle ? 0xFFFFFF : 0x777777);
        graphics.drawString(font, fit(mod.displayName(), width - left - 20), left, y + 3, 0xFFFFFF);
        int textWidth = width - left - 20;
        graphics.drawString(font, fit(mod.modId() + " · " + mod.version(), textWidth),
                left, y + 14, 0xCCCCCC);
        graphics.drawString(font, fit(mod.filename(), textWidth), left, y + 25, 0xFFCC77);
        Component status = mod.kind() == ClientCompatibilityReport.Kind.EXPLICIT_SERVER
                ? CleanupLabels.status(mod, cleanupSelection.serverDecision(mod.filename()), selected)
                : Component.translatable(mod.kind() == ClientCompatibilityReport.Kind.EXPLICIT_CLIENT
                        ? mod.verified() ? "jane.compat.explicit" : "jane.compat.unverified"
                        : "jane.compat.other");
        graphics.drawString(font, fit(status, textWidth), left, y + 36,
                canToggle ? 0x88CCFF : 0xFF7777);
    }

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (button != 0 || busy || mouseX < 10 || mouseX >= width - 10
                || mouseY < listTop() || mouseY >= listBottom()) return false;
        int y = listTop() - scroll;
        Row row = null;
        for (Row candidate : rows) {
            if (mouseY >= y && mouseY < y + candidate.height()) { row = candidate; break; }
            y += candidate.height();
        }
        if (row == null || row.mod() == null || !selectable(row.mod())) return true;
        boolean selected = !cleanupSelection.isSelected(row.mod().filename());
        if (cleanupSelection.setSelected(row.mod().filename(), selected))
            audit(selected ? "USER_SELECT_EXTRA_DISABLE" : "USER_DESELECT_EXTRA_DISABLE", Map.of(
                    "modId", row.mod().modId(), "selectedCount",
                    Integer.toString(cleanupSelection.selectedExtras().size())));
        return true;
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseY >= declarationTop() && mouseY < declarationBottom()) {
            int limit = Math.max(0, declaration().size() * 10
                    - (declarationBottom() - declarationTop()));
            declarationScroll = Math.max(0, Math.min(limit,
                    declarationScroll - (int) Math.round(scrollY * 18)));
            return true;
        }
        int visible = Math.max(0, listBottom() - listTop());
        int limit = Math.max(0, totalHeight() - visible);
        scroll = Math.max(0, Math.min(limit, scroll - (int) Math.round(scrollY * 24)));
        return true;
    }

    void openFinalConfirmation() {
        if (busy || cleanupSelection.selectedExtras().isEmpty()
                || !cleanupSelection.selectedServerDecisionsSafe()) return;
        ClientCleanupSelection.Snapshot snapshot = cleanupSelection.snapshot();
        busy = true;
        notice = Component.translatable("jane.compat.preparing_disable");
        CompletableFuture.supplyAsync(() -> {
            try { return prepareFresh(snapshot); }
            catch (Exception exception) { throw new java.util.concurrent.CompletionException(exception); }
        }).whenComplete((provisional, failure) -> minecraft.execute(() -> {
            busy = false;
            if (failure != null) {
                notice = Component.translatable("jane.compat.local_file_error");
            } else if (!cleanupSelection.stillMatches(snapshot, context, extras)) {
                notice = Component.translatable("jane.cleanup.plan_changed");
            } else if (minecraft.screen == this) {
                notice = null;
                minecraft.setScreen(new ClientUpdateConfirmScreen(this, 0,
                        cleanupSelection.selectedExtras(),
                        () -> cleanupSelection.stillMatches(snapshot, context, extras), () -> {
                            minecraft.setScreen(this);
                            disableSelected(snapshot, provisional.plan());
                        }));
            }
            if (minecraft.screen == this) rebuildWidgets();
        }));
    }

    private record PreparedCleanup(ClientDisablePlan plan) { }

    private PreparedCleanup prepareFresh(ClientCleanupSelection.Snapshot snapshot) throws IOException {
        Path gameDir = FMLPaths.GAMEDIR.get();
        ClientCompatibilityReport fresh = NeoForgeClientCompatibility.classify(gameDir,
                context.manifest(), NeoForgePhysicalDiscovery.discover(gameDir));
        if (!cleanupSelection.stillMatches(snapshot, context, fresh))
            throw new IOException("Confirmed client cleanup plan changed");
        List<ClientCompatibilityReport.ExtraMod> chosen = new ArrayList<>();
        for (ClientCleanupSelection.SelectedJar jar : snapshot.selectedJars()) {
            ClientCompatibilityReport.ExtraMod mod = fresh.explicitClientMods().stream()
                    .filter(candidate -> candidate.filename().equals(jar.filename())).findFirst()
                    .orElseGet(() -> fresh.otherExtraMods().stream()
                            .filter(candidate -> candidate.filename().equals(jar.filename())).findFirst()
                            .orElse(null));
            if (mod == null) throw new IOException("Confirmed client JAR disappeared");
            chosen.add(mod);
        }
        ClientDisablePlan disable = ClientDisablePlan.prepare(gameDir, context.serverId(),
                context.manifest(), fresh, chosen);
        if (!cleanupSelection.stillMatches(snapshot, context, fresh))
            throw new IOException("Confirmed client cleanup selection changed during verification");
        return new PreparedCleanup(disable);
    }

    private void disableSelected(ClientCleanupSelection.Snapshot snapshot, ClientDisablePlan provisional) {
        if (busy || !cleanupSelection.stillMatches(snapshot, context, extras)
                || snapshot.selectedJars().isEmpty()) {
            notice = Component.translatable("jane.cleanup.plan_changed");
            return;
        }
        audit("CLIENT_CLEANUP_CONFIRMED", Map.of("selectedCount",
                Integer.toString(snapshot.selectedJars().size())));
        busy = true;
        notice = Component.translatable("jane.compat.preparing_disable");
        Path gameDir = FMLPaths.GAMEDIR.get();
        CompletableFuture.supplyAsync(() -> {
            try {
                ClientDisablePlan disable = prepareFresh(snapshot).plan();
                if (!provisional.entries().equals(disable.entries())
                        || !provisional.dependencyGuards().equals(disable.dependencyGuards()))
                    throw new IOException("Confirmed client cleanup files changed");
                UpdatePlan update = new UpdatePlan(UUID.randomUUID().toString(), context.serverId(),
                        disable.timestamp(), List.of(), disable.entries(), disable.dependencyGuards());
                PendingStore.create(gameDir, update);
                PendingRecovery.verifyForLaunch(gameDir, update);
                return update;
            } catch (Exception exception) {
                throw new java.util.concurrent.CompletionException(exception);
            }
        }).whenComplete((plan, failure) -> minecraft.execute(() -> {
            busy = false;
            if (failure != null) {
                audit("CLIENT_CLEANUP_FAILED", Map.of("reason", "LOCAL_FILE_ERROR"));
                notice = Component.translatable("jane.compat.local_file_error");
            }
            else if (minecraft.screen == this) {
                try {
                    audit("CLIENT_CLEANUP_STARTED", Map.of("selectedCount",
                            Integer.toString(snapshot.selectedJars().size())));
                    UpdateLauncher.launch(minecraft, gameDir, plan);
                    return;
                } catch (Exception exception) {
                    audit("CLIENT_CLEANUP_FAILED", Map.of("reason", "LAUNCH_FAILED"));
                    notice = Component.translatable("jane.compat.launch_failed");
                }
            }
            if (minecraft.screen == this) rebuildWidgets();
        }));
    }

    @Override public void onClose() {
        if (!busy) super.onClose();
    }

    private boolean audit(String event, Map<String, String> fields) {
        Map<String, String> values = new HashMap<>(fields);
        values.put("serverId", context.serverId());
        values.put("manifestDigest", OneShotJoinOverride.manifestDigest(context.manifest()));
        try {
            JaneClientAudit.record(FMLPaths.GAMEDIR.get(), event, values);
            LOGGER.info("Jane compatibility decision {} for serverId={}", event,
                    context.serverId().substring(0, Math.min(12, context.serverId().length())));
            return true;
        } catch (IOException | SecurityException exception) {
            auditWarning = true;
            LOGGER.warn("Jane compatibility decision log failed for {}: {}", event,
                    exception.getClass().getSimpleName());
            return false;
        }
    }
}
