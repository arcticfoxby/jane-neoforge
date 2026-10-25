package dev.modsbyfox.jane.neoforge.client.ui;

import dev.modsbyfox.jane.core.ClientCompatibilityReport;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Explicit final approval of the exact client JARs to install and move after exit. */
final class ClientUpdateConfirmScreen extends UiScreen {
    record Layout(int listTop, int listBottom, int rowHeight) {
        static Layout forSize(int height, boolean blocked) {
            int top = height < 140 ? 41 : height < 170 ? 47 : 72;
            return new Layout(top, height - (blocked ? 70 : 57), height < 140 ? 24 : 31);
        }
        int maxScroll(int rowCount) {
            return Math.max(0, rowCount * rowHeight - Math.max(0, listBottom - listTop));
        }
    }

    private final int installCount;
    private final List<ClientCompatibilityReport.ExtraMod> cleanup;
    private final BooleanSupplier stillCurrent;
    private final Runnable confirm;
    private final Component blockReason;
    private Button confirmButton;
    private int scroll;

    ClientUpdateConfirmScreen(Screen parent, int installCount,
                              List<ClientCompatibilityReport.ExtraMod> cleanup,
                              BooleanSupplier stillCurrent, Runnable confirm) {
        this(parent, installCount, cleanup, stillCurrent, confirm, null);
    }

    ClientUpdateConfirmScreen(Screen parent, int installCount,
                              List<ClientCompatibilityReport.ExtraMod> cleanup,
                              BooleanSupplier stillCurrent, Runnable confirm, Component blockReason) {
        super(Component.translatable("jane.cleanup.confirm_title"), parent);
        if (installCount < 0 || cleanup == null || stillCurrent == null || confirm == null)
            throw new IllegalArgumentException("Invalid client update confirmation");
        this.installCount = installCount;
        this.cleanup = List.copyOf(cleanup);
        this.stillCurrent = stillCurrent;
        this.confirm = confirm;
        this.blockReason = blockReason;
    }

    @Override protected void init() {
        clearWidgets();
        int w = buttonWidth();
        int x = (width - w) / 2;
        confirmButton = addRenderableWidget(Button.builder(Component.translatable("jane.cleanup.confirm_action"),
                button -> {
                    if (blockReason == null && stillCurrent.getAsBoolean()
                            && (installCount > 0 || !cleanup.isEmpty())) confirm.run();
                }).bounds(x, height - 52, w, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("jane.cleanup.return_edit"),
                button -> onClose()).bounds(x, height - 28, w, 20).build());
    }

    private Layout layout() { return Layout.forSize(height, blockReason != null); }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        boolean current = stillCurrent.getAsBoolean();
        Layout layout = layout();
        confirmButton.active = current && blockReason == null && (installCount > 0 || !cleanup.isEmpty());
        centered(graphics, title, height < 140 ? 4 : 7, 0xFFFFFF);
        centered(graphics, Component.translatable("jane.cleanup.install_count", installCount),
                height < 140 ? 16 : 22, 0xFFCC77);
        centered(graphics, Component.translatable("jane.cleanup.move_count", cleanup.size()),
                height < 140 ? 28 : 34, 0x88CCFF);
        if (height >= 170) {
            centered(graphics, Component.translatable("jane.cleanup.after_exit"), 48, 0xCCCCCC);
            centered(graphics, Component.translatable("jane.cleanup.backup"), 60, 0xAAFFAA);
        }
        int top = layout.listTop();
        int bottom = layout.listBottom();
        if (bottom > top) {
            scroll = Math.min(scroll, layout.maxScroll(cleanup.size()));
            graphics.enableScissor(10, top, width - 10, bottom);
            int y = top - scroll;
            for (ClientCompatibilityReport.ExtraMod mod : cleanup) {
                if (y + layout.rowHeight() > top && y < bottom) {
                    graphics.fill(10, y, width - 10, y + layout.rowHeight() - 2, 0x88000000);
                    graphics.drawString(font, fit(mod.displayName(), width - 32), 16, y + 3, 0xFFFFFF);
                    graphics.drawString(font, fit(mod.filename(), width - 32), 16,
                            y + (height < 140 ? 13 : 17), 0xFFCC77);
                }
                y += layout.rowHeight();
            }
            graphics.disableScissor();
        }
        if (!current || blockReason != null)
            graphics.drawCenteredString(font, fit(!current
                    ? Component.translatable("jane.cleanup.plan_changed") : blockReason, width - 20),
                    width / 2, height - 64, 0xFF7777);
        renderWidgets(graphics, mouseX, mouseY, partialTick);
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        Layout layout = layout();
        int limit = layout.maxScroll(cleanup.size());
        scroll = Math.max(0, Math.min(limit, scroll - (int) Math.round(scrollY * 24)));
        return true;
    }
}
