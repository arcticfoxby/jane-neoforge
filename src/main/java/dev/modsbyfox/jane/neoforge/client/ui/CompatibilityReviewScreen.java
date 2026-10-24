package dev.modsbyfox.jane.neoforge.client.ui;

import dev.modsbyfox.jane.core.ClientCompatibilityReport;
import dev.modsbyfox.jane.core.ClientDisableBatch;
import dev.modsbyfox.jane.core.ClientDisablePlan;
import dev.modsbyfox.jane.core.OneShotJoinOverride;
import dev.modsbyfox.jane.core.PendingSyncContext;
import dev.modsbyfox.jane.neoforge.client.JaneClientAudit;
import dev.modsbyfox.jane.core.SyncSelection;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private record Row(Component heading, ClientCompatibilityReport.ExtraMod mod, boolean selectable) { }

    private final PendingSyncContext context;
    private final ClientCompatibilityReport extras;
    private boolean auditWarning;
    private final JaneClientUi.JoinActions actions;
    private final Set<String> selectedFiles = new LinkedHashSet<>();
    private final List<Row> rows;
    private int scroll;
    private int declarationScroll;
    private boolean busy;
    private Component notice;
    private Button disableButton;
    private Button directButton;

    CompatibilityReviewScreen(Screen parent, PendingSyncContext context, ClientCompatibilityReport extras,
                              boolean auditSaveFailed, JaneClientUi.JoinActions actions) {
        super(Component.translatable("jane.compat.title"), parent);
        this.context = context;
        this.extras = extras;
        this.auditWarning = auditSaveFailed;
        this.actions = actions;
        List<Row> all = new ArrayList<>();
        addGroup(all, "jane.compat.client_group", extras.explicitClientMods(), true);
        addGroup(all, "jane.compat.server_group", extras.declaredServerOnlyMods(), false);
        addGroup(all, "jane.compat.other_group", extras.unclassifiedExtraMods(), false);
        rows = List.copyOf(all);
    }

    private static void addGroup(List<Row> rows, String headingKey,
                                 List<ClientCompatibilityReport.ExtraMod> mods, boolean selectable) {
        if (mods.isEmpty()) return;
        rows.add(new Row(Component.translatable(headingKey, mods.size()), null, false));
        for (ClientCompatibilityReport.ExtraMod mod : mods)
            rows.add(new Row(null, mod, selectable && mod.sha512() != null));
    }

    @Override protected void init() {
        clearWidgets();
        int w = buttonWidth();
        int x = (width - w) / 2;
        boolean compact = height < 190;
        int half = (w - 4) / 2;
        disableButton = addRenderableWidget(Button.builder(Component.translatable(compact
                ? "jane.compat.disable_short" : "jane.compat.disable"),
                button -> minecraft.setScreen(new DisableConfirmScreen(this, selectedFiles.size())))
                .bounds(x, height - (compact ? 52 : 76), compact ? half : w, 20).build());
        directButton = addRenderableWidget(Button.builder(Component.translatable(compact
                ? "jane.compat.direct_short" : "jane.compat.direct_join"),
                button -> directJoin())
                .bounds(compact ? x + half + 4 : x, height - 52, compact ? half : w, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), button -> onClose())
                .bounds(x, height - 28, w, 20).build());
    }

    private boolean compact() { return height < 190; }
    private int declarationTop() { return compact() ? 32 : 38; }
    private int declarationBottom() { return compact() ? 53 : 91; }
    private int listTop() { return compact() ? 56 : 96; }
    private int listBottom() { return height - (compact() ? (notice == null && !auditWarning ? 58 : 80) : 83); }

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
        disableButton.active = !busy && !selectedFiles.isEmpty();
        directButton.active = !busy;
        centered(graphics, title, 7, 0xFFFFFF);
        centered(graphics, Component.translatable("jane.compat.required_pass"), compact() ? 20 : 23, 0xAAFFAA);
        int declarationTop = declarationTop();
        int declarationBottom = declarationBottom();
        List<FormattedCharSequence> text = declaration();
        declarationScroll = Math.min(declarationScroll,
                Math.max(0, text.size() * 10 - (declarationBottom - declarationTop)));
        graphics.fill(10, declarationTop, width - 10, declarationBottom, 0x88000000);
        graphics.enableScissor(12, declarationTop, width - 12, declarationBottom);
        int y = declarationTop - declarationScroll;
        for (FormattedCharSequence line : text) {
            if (line != null && y + 10 > declarationTop && y < declarationBottom)
                graphics.drawString(font, line, 16, y, 0xBBBBBB);
            y += 10;
        }
        graphics.disableScissor();
        int top = listTop();
        int bottom = listBottom();
        int rowHeight = 31;
        scroll = Math.min(scroll, Math.max(0, rows.size() * rowHeight - Math.max(0, bottom - top)));
        if (bottom > top) {
            graphics.enableScissor(10, top, width - 10, bottom);
            int rowY = top - scroll;
            for (Row row : rows) {
                if (rowY + rowHeight > top && rowY < bottom)
                    drawRow(graphics, row, rowY, mouseX, mouseY);
                rowY += rowHeight;
            }
            graphics.disableScissor();
        }
        Component shownNotice = notice != null ? notice : auditWarning
                ? Component.translatable("jane.select.audit_incomplete") : null;
        if (shownNotice != null)
            centered(graphics, shownNotice, compact() ? height - 75 : height - 89, 0xFF7777);
        renderWidgets(graphics, mouseX, mouseY, partialTick);
    }

    private void drawRow(GuiGraphics graphics, Row row, int y, int mouseX, int mouseY) {
        if (row.heading() != null) {
            graphics.fill(10, y, width - 10, y + 29, 0xAA223344);
            graphics.drawString(font, fit(row.heading(), width - 32), 16, y + 10, 0x66DDFF);
            return;
        }
        ClientCompatibilityReport.ExtraMod mod = row.mod();
        boolean explicit = mod.kind() == ClientCompatibilityReport.Kind.EXPLICIT_CLIENT;
        graphics.fill(10, y, width - 10, y + 29,
                mouseX >= 10 && mouseX < width - 10 && mouseY >= y && mouseY < y + 31
                        ? 0xAA444444 : 0x88000000);
        int left = row.selectable() ? 33 : 16;
        if (row.selectable()) graphics.drawString(font,
                selectedFiles.contains(mod.filename()) ? "☑" : "☐", 16, y + 10, 0xFFFFFF);
        graphics.drawString(font, fit(mod.displayName(), width - left - 20), left, y + 3, 0xFFFFFF);
        Component kind = Component.translatable(switch (mod.kind()) {
            case EXPLICIT_CLIENT -> "jane.compat.explicit";
            case EXPLICIT_SERVER -> "jane.compat.server_only";
            case OTHER_EXTRA -> "jane.compat.other";
        });
        String detail = mod.modId() + " · " + mod.version() + " · " + kind.getString();
        if (explicit && !row.selectable())
            detail += " · " + Component.translatable("jane.compat.unverified").getString();
        graphics.drawString(font, fit(detail, width - left - 20), left, y + 17,
                explicit && !row.selectable() ? 0xFF7777 : explicit ? 0xFFCC77
                        : mod.kind() == ClientCompatibilityReport.Kind.EXPLICIT_SERVER ? 0x88CCFF : 0xBBBBBB);
    }

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (button != 0 || busy || mouseX < 10 || mouseX >= width - 10
                || mouseY < listTop() || mouseY >= listBottom()) return false;
        int index = ((int) mouseY - listTop() + scroll) / 31;
        if (index < 0 || index >= rows.size()) return false;
        Row row = rows.get(index);
        if (row.mod() == null || !row.selectable()) return true;
        boolean selected = selectedFiles.add(row.mod().filename());
        if (!selected) selectedFiles.remove(row.mod().filename());
        audit(selected ? "USER_SELECT_EXTRA_DISABLE" : "USER_DESELECT_EXTRA_DISABLE", Map.of(
                "modId", row.mod().modId(), "selectedCount", Integer.toString(selectedFiles.size())));
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
        int limit = Math.max(0, rows.size() * 31 - visible);
        scroll = Math.max(0, Math.min(limit, scroll - (int) Math.round(scrollY * 24)));
        return true;
    }

    void disableSelected() {
        if (busy || selectedFiles.isEmpty()) return;
        audit("CLIENT_DISABLE_CONFIRMED", Map.of("selectedCount", Integer.toString(selectedFiles.size())));
        busy = true;
        notice = Component.translatable("jane.compat.preparing_disable");
        List<ClientCompatibilityReport.ExtraMod> chosen = extras.explicitClientMods().stream()
                .filter(mod -> selectedFiles.contains(mod.filename())).toList();
        Path gameDir = FMLPaths.GAMEDIR.get();
        CompletableFuture.supplyAsync(() -> {
            try {
                ClientDisablePlan plan = ClientDisablePlan.prepare(gameDir, context.serverId(), extras, chosen);
                ClientDisableBatch.write(gameDir, plan, ProcessHandle.current().pid());
                return plan;
            } catch (Exception exception) {
                throw new java.util.concurrent.CompletionException(exception);
            }
        }).whenComplete((plan, failure) -> minecraft.execute(() -> {
            busy = false;
            if (failure != null) {
                audit("CLIENT_DISABLE_FAILED", Map.of("reason", "LOCAL_FILE_ERROR"));
                notice = Component.translatable("jane.compat.local_file_error");
            }
            else if (minecraft.screen == this) {
                try {
                    UpdateLauncher.launchDisable(minecraft, gameDir, plan);
                    return;
                } catch (Exception exception) {
                    audit("CLIENT_DISABLE_FAILED", Map.of("reason", "LAUNCH_FAILED"));
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
