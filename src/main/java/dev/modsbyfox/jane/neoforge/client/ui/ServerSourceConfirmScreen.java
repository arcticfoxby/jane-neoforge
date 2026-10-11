package dev.modsbyfox.jane.neoforge.client.ui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** Separate trust decision before downloading JARs without a public exact-hash source. */
final class ServerSourceConfirmScreen extends UiScreen {
    private final SyncScreen owner;
    private final long count;
    private final long bytes;

    ServerSourceConfirmScreen(SyncScreen owner, long count, long bytes) {
        super(Component.translatable("jane.server_confirm.title"), owner);
        this.owner = owner;
        this.count = count;
        this.bytes = bytes;
    }

    @Override protected void init() {
        clearWidgets();
        int w = buttonWidth();
        int x = (width - w) / 2;
        addRenderableWidget(Button.builder(Component.translatable("jane.server_confirm.download"),
                button -> owner.confirmServerOnly()).bounds(x, height - 52, w, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.back"),
                button -> onClose()).bounds(x, height - 28, w, 20).build());
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        boolean compact = height < 165;
        centered(graphics, title, compact ? 3 : 9, 0xFFFFFF);
        if (!compact) centered(graphics, Component.translatable("jane.server_confirm.description"), 30, 0xDDDDDD);
        centered(graphics, Component.translatable("jane.server_confirm.warning"), compact ? 19 : 48, 0xFFCC77);
        if (!compact) centered(graphics, Component.translatable("jane.server_confirm.server",
                owner.session().context().serverAddress()), 69, 0xBBBBBB);
        centered(graphics, Component.translatable("jane.server_confirm.count", count,
                String.format(java.util.Locale.ROOT, "%.1f", bytes / 1048576.0)), compact ? 34 : 84, 0xBBBBBB);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override public void onClose() {
        owner.declineServerOnly();
        super.onClose();
    }
}
