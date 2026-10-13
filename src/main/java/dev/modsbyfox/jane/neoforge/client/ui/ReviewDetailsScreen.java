package dev.modsbyfox.jane.neoforge.client.ui;

import dev.modsbyfox.jane.core.ClientCompatibilityReport;
import dev.modsbyfox.jane.core.Comparison;
import dev.modsbyfox.jane.core.PendingSyncContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Scrollable local-only details; the server never receives this inventory. */
final class ReviewDetailsScreen extends UiScreen {
    private record Row(Component title, Component detail, int color) { }
    private final List<Row> rows;
    private int scroll;

    ReviewDetailsScreen(Screen parent, PendingSyncContext context, ClientCompatibilityReport extras) {
        super(Component.translatable("jane.environment.details"), parent);
        List<Row> all = new ArrayList<>();
        for (Comparison.Result result : context.results()) {
            Component state = Component.translatable("jane.status."
                    + result.status().name().toLowerCase(Locale.ROOT));
            all.add(new Row(Component.literal(result.required().displayName()),
                    Component.translatable("jane.direct.required_state", result.required().version(), state),
                    result.status() == Comparison.Status.OK ? 0xAAFFAA : 0xFFCC77));
        }
        for (ClientCompatibilityReport.ExtraMod extra : extras.explicitClientMods())
            all.add(new Row(Component.literal(extra.displayName()),
                    Component.translatable("jane.direct.extra_id", extra.modId(), extra.version()), 0xFFCC77));
        for (ClientCompatibilityReport.ExtraMod extra : extras.otherExtraMods())
            all.add(new Row(Component.literal(extra.displayName()),
                    Component.translatable("jane.direct.extra_id", extra.modId(), extra.version()), 0xBBBBBB));
        rows = List.copyOf(all);
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
        int top = 27;
        int bottom = height - 29;
        int rowHeight = 30;
        int maxScroll = Math.max(0, rows.size() * rowHeight - Math.max(0, bottom - top));
        scroll = Math.min(scroll, maxScroll);
        if (bottom > top) {
            graphics.enableScissor(10, top, width - 10, bottom);
            int y = top - scroll;
            for (Row row : rows) {
                if (y + rowHeight > top && y < bottom) {
                    graphics.fill(10, y, width - 10, y + rowHeight - 2, 0x88000000);
                    graphics.drawString(font, fit(row.title(), width - 32), 16, y + 3, 0xFFFFFF);
                    graphics.drawString(font, fit(row.detail(), width - 32), 16, y + 16, row.color());
                }
                y += rowHeight;
            }
            graphics.disableScissor();
        }
        renderWidgets(graphics, mouseX, mouseY, partialTick);
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int visible = Math.max(0, height - 56);
        int limit = Math.max(0, rows.size() * 30 - visible);
        scroll = Math.max(0, Math.min(limit, scroll - (int) Math.round(scrollY * 24)));
        return true;
    }
}
