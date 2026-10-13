package dev.modsbyfox.jane.neoforge.client.ui;

import dev.modsbyfox.jane.core.ClientCompatibilityReport;
import dev.modsbyfox.jane.core.Comparison;
import dev.modsbyfox.jane.core.OneShotJoinOverride;
import dev.modsbyfox.jane.core.PendingSyncContext;
import dev.modsbyfox.jane.core.SyncSelection;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Presents the local environment result before any source lookup or file operation. */
final class EnvironmentReviewScreen extends UiScreen {
    private final PendingSyncContext context;
    private final ClientCompatibilityReport extras;
    private final boolean auditSaveFailed;
    private final JaneClientUi.JoinActions actions;
    private Button joinButton;

    EnvironmentReviewScreen(Screen parent, PendingSyncContext context,
                            ClientCompatibilityReport extras, boolean auditSaveFailed,
                            JaneClientUi.JoinActions actions) {
        super(Component.translatable("jane.environment.title"), parent);
        this.context = context;
        this.extras = extras;
        this.auditSaveFailed = auditSaveFailed;
        this.actions = actions;
    }

    private boolean passed() { return Comparison.passed(context.results()); }

    private int extraCount() { return extras.explicitClientMods().size() + extras.otherExtraMods().size(); }

    @Override protected void init() {
        clearWidgets();
        int w = buttonWidth();
        int x = (width - w) / 2;
        boolean small = height < 190;
        int base = height - (small ? 52 : 104);
        if (small) {
            int half = Math.max(1, (w - 4) / 2);
            addRenderableWidget(Button.builder(Component.translatable("jane.environment.sync_short"),
                    button -> sync()).bounds(x, base, half, 20).build());
            joinButton = addRenderableWidget(Button.builder(Component.translatable("jane.environment.direct_short"),
                    button -> direct()).bounds(x + half + 4, base, half, 20).build());
            addRenderableWidget(Button.builder(Component.translatable("jane.environment.details_short"),
                    button -> minecraft.setScreen(new ReviewDetailsScreen(this, context, extras)))
                    .bounds(x, base + 24, half, 20).build());
            addRenderableWidget(Button.builder(Component.translatable("gui.back"),
                    button -> onClose()).bounds(x + half + 4, base + 24, half, 20).build());
        } else {
            addRenderableWidget(Button.builder(Component.translatable("jane.environment.sync"),
                    button -> sync()).bounds(x, base, w, 20).build());
            joinButton = addRenderableWidget(Button.builder(Component.translatable("jane.environment.direct"),
                    button -> direct()).bounds(x, base + 24, w, 20).build());
            addRenderableWidget(Button.builder(Component.translatable("jane.environment.details"),
                    button -> minecraft.setScreen(new ReviewDetailsScreen(this, context, extras)))
                    .bounds(x, base + 48, w, 20).build());
            addRenderableWidget(Button.builder(Component.translatable("gui.back"),
                    button -> onClose()).bounds(x, base + 72, w, 20).build());
        }
        joinButton.active = passed() || OneShotJoinOverride.overrideable(context.manifest(), context.results());
    }

    private void sync() {
        if (passed()) minecraft.setScreen(new CompatibilityReviewScreen(this, context, extras,
                auditSaveFailed, actions));
        else minecraft.setScreen(new SyncScreen(this, context, extras, auditSaveFailed, actions));
    }

    private void direct() {
        if (!joinButton.active) return;
        SyncSelection selection = new SyncSelection(context);
        if (!passed()) {
            for (Comparison.Result result : context.results())
                if (result.status() != Comparison.Status.OK)
                    selection.setSelected(result.required().modId(), false);
        }
        minecraft.setScreen(new JoinRiskScreen(this, context, extras, selection, auditSaveFailed, actions));
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        centered(graphics, title, 8, 0xFFFFFF);
        int required = context.results().size();
        long matched = context.results().stream().filter(result -> result.status() == Comparison.Status.OK).count();
        long localErrors = context.results().stream().filter(result -> result.status() == Comparison.Status.FILE_ERROR
                || result.status() != Comparison.Status.OK && !result.retainedCandidates().isEmpty()).count();
        int start = height < 150 ? 23 : 31;
        int actionTop = height - (height < 190 ? 52 : 104);
        int warningY = start + 52 + 10 <= actionTop - 4 ? start + 52 : start + 39;
        boolean warning = (localErrors > 0 || auditSaveFailed) && height >= 178;
        centered(graphics, Component.translatable("jane.environment.required", required), start, 0xDDDDDD);
        centered(graphics, Component.translatable("jane.environment.matched", matched), start + 13, 0xAAFFAA);
        if (height >= 130)
            centered(graphics, Component.translatable("jane.environment.unmet", required - matched), start + 26,
                    required == matched ? 0xAAFFAA : 0xFFCC77);
        if (height >= 165 && !(warning && warningY == start + 39))
            centered(graphics, Component.translatable("jane.environment.extras", extraCount()), start + 39, 0xFFCC77);
        if (localErrors > 0 && warning)
            centered(graphics, Component.translatable("jane.environment.file_error", localErrors), warningY, 0xFF7777);
        else if (auditSaveFailed && warning)
            centered(graphics, Component.translatable("jane.select.audit_incomplete"), warningY, 0xFF7777);
        renderWidgets(graphics, mouseX, mouseY, partialTick);
    }
}
