package dev.modsbyfox.jane.neoforge.client.ui;

import dev.modsbyfox.jane.core.ClientCompatibilityReport;
import dev.modsbyfox.jane.core.Comparison;
import dev.modsbyfox.jane.core.OneShotJoinOverride;
import dev.modsbyfox.jane.core.PendingSyncContext;
import dev.modsbyfox.jane.core.SyncSelection;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** Explicit, single-use confirmation before joining with local differences. */
final class JoinRiskScreen extends UiScreen {
    private record Line(FormattedCharSequence text, int color) { }
    private record Risk(Component text, int color) { }
    private final PendingSyncContext context;
    private final ClientCompatibilityReport extras;
    private final SyncSelection selection;
    private final boolean auditSaveFailed;
    private final JaneClientUi.JoinActions actions;
    private final List<Risk> risks;
    private final int unmatchedCount;
    private final int extraCount;
    private int scroll;
    private boolean joining;
    private boolean auditWarningShown;
    private Component notice;
    private Button confirm;

    JoinRiskScreen(Screen parent, PendingSyncContext context, ClientCompatibilityReport extras,
                   SyncSelection selection, boolean auditSaveFailed, JaneClientUi.JoinActions actions) {
        super(Component.translatable("jane.direct.title"), parent);
        this.context = context;
        this.extras = extras;
        this.selection = selection;
        this.auditSaveFailed = auditSaveFailed;
        this.actions = actions;
        unmatchedCount = (int) context.results().stream()
                .filter(result -> result.status() != Comparison.Status.OK).count();
        extraCount = extras.explicitClientMods().size() + extras.otherExtraMods().size();
        List<Risk> lines = new ArrayList<>();
        lines.add(new Risk(Component.translatable("jane.direct.required_heading", unmatchedCount), 0x66DDFF));
        if (unmatchedCount == 0) lines.add(new Risk(Component.translatable("jane.direct.none"), 0xBBBBBB));
        for (Comparison.Result result : context.results()) {
            if (result.status() == Comparison.Status.OK) continue;
            lines.add(new Risk(Component.literal(result.required().displayName() + " ["
                    + result.required().modId() + "]"), 0xFFFFFF));
            lines.add(new Risk(Component.translatable("jane.direct.required_state", result.required().version(),
                    Component.translatable("jane.status." + result.status().name().toLowerCase(Locale.ROOT))),
                    0xFFCC77));
        }
        lines.add(new Risk(null, 0));
        lines.add(new Risk(Component.translatable("jane.direct.extra_heading", extraCount), 0x66DDFF));
        if (extraCount == 0) lines.add(new Risk(Component.translatable("jane.direct.none"), 0xBBBBBB));
        for (ClientCompatibilityReport.ExtraMod extra : extras.explicitClientMods()) {
            lines.add(new Risk(Component.literal(extra.displayName()), 0xFFFFFF));
            lines.add(new Risk(Component.translatable("jane.direct.extra_id", extra.modId(), extra.version()),
                    0xFFCC77));
        }
        for (ClientCompatibilityReport.ExtraMod extra : extras.otherExtraMods()) {
            lines.add(new Risk(Component.literal(extra.displayName()), 0xFFFFFF));
            lines.add(new Risk(Component.translatable("jane.direct.extra_id", extra.modId(), extra.version()),
                    0xFFCC77));
        }
        risks = List.copyOf(lines);
    }

    private boolean mayJoin() {
        if (Comparison.passed(context.results())) return true;
        if (!OneShotJoinOverride.overrideable(context.manifest(), context.results())) return false;
        for (Comparison.Result result : context.results())
            if (result.status() != Comparison.Status.OK && selection.isSelected(result.required().modId())) return false;
        return true;
    }

    private List<Line> displayLines() {
        List<Line> lines = new ArrayList<>();
        int lineWidth = Math.max(80, width - 42);
        for (int i = 1; i <= 3; i++) {
            if ((i == 2 && unmatchedCount == 0) || (i == 3 && extraCount == 0)) continue;
            for (FormattedCharSequence text : font.split(Component.translatable("jane.direct.explain" + i), lineWidth))
                lines.add(new Line(text, i == 1 ? 0xCCCCCC : 0xFFCC77));
            lines.add(new Line(null, 0));
        }
        for (Risk risk : risks) {
            if (risk.text() == null) {
                lines.add(new Line(null, 0));
                continue;
            }
            for (FormattedCharSequence text : font.split(risk.text(), lineWidth))
                lines.add(new Line(text, risk.color()));
        }
        return lines;
    }

    @Override protected void init() {
        clearWidgets();
        int w = buttonWidth();
        int x = (width - w) / 2;
        confirm = addRenderableWidget(Button.builder(Component.translatable("jane.direct.confirm"),
                button -> confirm()).bounds(x, height - 52, w, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("jane.direct.back_sync"),
                button -> onClose()).bounds(x, height - 28, w, 20).build());
    }

    private void confirm() {
        if (joining || !mayJoin()) return;
        if (auditSaveFailed && !auditWarningShown) {
            auditWarningShown = true;
            notice = Component.translatable("jane.direct.audit_continue");
            return;
        }
        joining = true;
        boolean accepted = Comparison.passed(context.results())
                ? actions.reconnectWithExtras(context, extras)
                : actions.reconnectWithRequiredOverride(context, selection);
        if (!accepted) {
            joining = false;
            notice = actions.failureNotice();
        }
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        confirm.active = mayJoin() && !joining;
        centered(graphics, title, 7, 0xFFFFFF);
        int top = 30;
        int bottom = Math.max(top, height - (notice == null ? 58 : 75));
        List<Line> lines = displayLines();
        int contentHeight = lines.size() * 12;
        scroll = Math.min(scroll, Math.max(0, contentHeight - (bottom - top)));
        if (bottom > top) {
            graphics.enableScissor(12, top, width - 12, bottom);
            int y = top - scroll;
            for (Line line : lines) {
                if (line.text() != null && y + 12 > top && y < bottom)
                    graphics.drawString(font, line.text(), 18, y + 1, line.color());
                y += 12;
            }
            graphics.disableScissor();
        }
        if (notice != null) centered(graphics, notice, height - 64, 0xFF7777);
        renderWidgets(graphics, mouseX, mouseY, partialTick);
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int visible = Math.max(0, height - (notice == null ? 88 : 105));
        scroll = Math.max(0, Math.min(Math.max(0, displayLines().size() * 12 - visible),
                scroll - (int) Math.round(scrollY * 20)));
        return true;
    }
}
