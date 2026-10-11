package dev.modsbyfox.jane.neoforge.client.ui;

import dev.modsbyfox.jane.core.Comparison;
import dev.modsbyfox.jane.core.JaneSyncSession;
import dev.modsbyfox.jane.core.ResolutionPlan;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** Local-only, scrollable status of each Required file and its current transfer. */
final class SyncDetailsScreen extends UiScreen {
    private static final int ROW_HEIGHT = 49;
    private final JaneSyncSession session;
    private int scroll;

    SyncDetailsScreen(UiScreen parent, JaneSyncSession session) {
        super(Component.translatable("jane.sync.details_title"), parent);
        this.session = session;
    }

    @Override protected void init() {
        clearWidgets();
        int w = Math.min(150, buttonWidth());
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), button -> onClose())
                .bounds((width - w) / 2, height - 24, w, 20).build());
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        centered(graphics, title, 8, 0xFFFFFF);
        List<JaneSyncSession.ItemState> items = session.snapshot().items();
        int top = 24;
        int bottom = height - 29;
        scroll = Math.min(scroll, Math.max(0, items.size() * ROW_HEIGHT - Math.max(0, bottom - top)));
        if (bottom > top) {
            graphics.enableScissor(10, top, width - 10, bottom);
            int y = top - scroll;
            for (JaneSyncSession.ItemState item : items) {
                if (y + ROW_HEIGHT > top && y < bottom) drawItem(graphics, item, y);
                y += ROW_HEIGHT;
            }
            graphics.disableScissor();
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void drawItem(GuiGraphics graphics, JaneSyncSession.ItemState item, int y) {
        Comparison.Result result = item.item().comparison();
        ResolutionPlan.Availability availability = item.item().availability();
        JaneSyncSession.RuntimeState state = item.state();
        int color = state == JaneSyncSession.RuntimeState.LOCAL_ERROR
                || state == JaneSyncSession.RuntimeState.FAILED
                || availability == ResolutionPlan.Availability.LOCAL_ERROR ? 0xFF7777
                : state == JaneSyncSession.RuntimeState.READY
                || availability == ResolutionPlan.Availability.ALREADY_PRESENT ? 0xAAFFAA : 0xFFCC77;
        int left = 16;
        int textWidth = width - 32;
        graphics.fill(10, y, width - 10, y + ROW_HEIGHT - 2, 0x88000000);
        graphics.drawString(font, fit(result.required().displayName(), textWidth), left, y + 2, 0xFFFFFF);
        graphics.drawString(font, fit(result.required().modId() + " · " + result.required().version()
                + " · " + Component.translatable("jane.status."
                + result.status().name().toLowerCase(Locale.ROOT)).getString(), textWidth),
                left, y + 13, 0xCCCCCC);
        Component source = Component.translatable("jane.sync.availability."
                + availability.name().toLowerCase(Locale.ROOT));
        Component transfer = Component.translatable("jane.sync.state."
                + (state == null ? "not_started" : state.name().toLowerCase(Locale.ROOT)));
        graphics.drawString(font, fit(source.getString() + " · " + transfer.getString(), textWidth),
                left, y + 25, color);
        long size = result.required().fileSize();
        long bytes = Math.max(0, Math.min(size, item.downloadedBytes()));
        int percent = availability == ResolutionPlan.Availability.ALREADY_PRESENT
                || state == JaneSyncSession.RuntimeState.READY ? 100 : (int) (bytes * 100 / size);
        String progress = Component.translatable("jane.sync.details_progress", percent).getString();
        if (item.completedVia() != null)
            progress += " · " + Component.translatable("jane.sync.details_via",
                    Component.translatable(item.completedVia() == ResolutionPlan.TransferRoute.CURRENT_SERVER
                            ? "jane.sync.source_server" : "jane.sync.source_trusted")).getString();
        graphics.drawString(font, fit(progress, textWidth), left, y + 36, 0xBBBBBB);
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int visible = Math.max(0, height - 53);
        int limit = Math.max(0, session.snapshot().items().size() * ROW_HEIGHT - visible);
        scroll = Math.max(0, Math.min(limit, scroll - (int) Math.round(scrollY * 24)));
        return true;
    }
}
