package dev.modsbyfox.jane.neoforge.client.ui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Small shared layout helpers for Jane's native Minecraft screens. */
abstract class UiScreen extends Screen {
    protected final Screen parent;

    protected UiScreen(Component title, Screen parent) {
        super(title);
        this.parent = parent;
    }

    protected final String fit(Component value, int maxWidth) {
        return fit(value.getString(), maxWidth);
    }

    protected final String fit(String value, int maxWidth) {
        if (font.width(value) <= maxWidth) return value;
        return font.plainSubstrByWidth(value, Math.max(0, maxWidth - font.width("…"))) + "…";
    }

    protected final void centered(GuiGraphics graphics, Component value, int y, int color) {
        graphics.drawCenteredString(font, fit(value, width - 20), width / 2, y, color);
    }

    protected final int buttonWidth() { return Math.max(1, Math.min(240, width - 24)); }

    /** Screen.render draws the background again, so render widgets after Jane's text without reblurring it. */
    protected final void renderWidgets(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        for (Renderable renderable : renderables)
            renderable.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override public void onClose() { minecraft.setScreen(parent); }
}
