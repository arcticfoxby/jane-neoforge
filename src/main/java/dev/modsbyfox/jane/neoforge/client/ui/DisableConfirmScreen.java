package dev.modsbyfox.jane.neoforge.client.ui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** Second explicit action before moving selected client-only JARs after exit. */
final class DisableConfirmScreen extends UiScreen {
    private final CompatibilityReviewScreen review;
    private final int count;

    DisableConfirmScreen(CompatibilityReviewScreen review, int count) {
        super(Component.translatable("jane.compat.confirm_title"), review);
        this.review = review;
        this.count = count;
    }

    @Override protected void init() {
        clearWidgets();
        int w = buttonWidth();
        int x = (width - w) / 2;
        addRenderableWidget(Button.builder(Component.translatable("jane.compat.confirm_disable"), button -> {
            minecraft.setScreen(review);
            review.disableSelected();
        }).bounds(x, height - 52, w, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), button -> onClose())
                .bounds(x, height - 28, w, 20).build());
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        centered(graphics, title, 8, 0xFFFFFF);
        centered(graphics, Component.translatable("jane.compat.confirm_count", count), 28, 0xFFCC77);
        centered(graphics, Component.translatable("jane.compat.confirm_explain"), 45, 0xCCCCCC);
        if (height >= 150)
            centered(graphics, Component.translatable("jane.compat.dependency_warning"), 59, 0xFFCC77);
        super.render(graphics, mouseX, mouseY, partialTick);
    }
}
