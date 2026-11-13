package dev.modsbyfox.jane.neoforge.client.ui;

import dev.modsbyfox.jane.core.ClientCompatibilityReport;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** A separate acknowledgement before moving a manually selected, unclassified physical JAR. */
final class OtherExtraRiskConfirmScreen extends UiScreen {
    private static final int LINE_HEIGHT = 12;
    private static final int BAR_WIDTH = 7;

    record Layout(int contentTop, int contentBottom, int confirmY, int cancelY,
                  boolean readable) {
        static Layout forSize(int width, int height) {
            int confirmY = height - 52;
            int top = height <= 170 ? 34 : 44;
            int bottom = confirmY - 4;
            return new Layout(top, bottom, confirmY, height - 28,
                    width >= 170 && bottom - top >= 22);
        }

        int visibleHeight() { return Math.max(0, contentBottom - contentTop); }
        int maxScroll(int contentHeight) { return Math.max(0, contentHeight - visibleHeight()); }
    }

    private record Line(FormattedCharSequence text, int color) { }

    private final List<ClientCompatibilityReport.ExtraMod> other;
    private final int totalMoveCount;
    private final BooleanSupplier stillCurrent;
    private final Runnable confirm;
    private Button confirmButton;
    private int scroll;
    private boolean dragging;
    private int grabOffset;

    OtherExtraRiskConfirmScreen(Screen parent, List<ClientCompatibilityReport.ExtraMod> cleanup,
                                BooleanSupplier stillCurrent, Runnable confirm) {
        super(Component.translatable("jane.cleanup.other_confirm_title"), parent);
        if (cleanup == null || stillCurrent == null || confirm == null)
            throw new IllegalArgumentException("Missing cleanup confirmation context");
        this.other = cleanup.stream().filter(mod ->
                mod.kind() == ClientCompatibilityReport.Kind.OTHER_EXTRA).toList();
        if (other.isEmpty()) throw new IllegalArgumentException("No other extra JAR selected");
        this.totalMoveCount = cleanup.size();
        this.stillCurrent = stillCurrent;
        this.confirm = confirm;
    }

    @Override protected void init() {
        clearWidgets();
        dragging = false;
        Layout layout = Layout.forSize(width, height);
        int w = buttonWidth();
        int x = (width - w) / 2;
        confirmButton = addRenderableWidget(Button.builder(Component.translatable("jane.cleanup.other_confirm_action"),
                button -> {
                    if (layout.readable() && stillCurrent.getAsBoolean()
                            && scroll >= layout.maxScroll(contentHeight())) confirm.run();
                }).bounds(x, layout.confirmY(), w, 20).build());
        confirmButton.active = false;
        addRenderableWidget(Button.builder(Component.translatable("jane.cleanup.other_confirm_cancel"),
                button -> onClose()).bounds(x, layout.cancelY(), w, 20).build());
    }

    private List<Line> lines() {
        List<Line> result = new ArrayList<>();
        addWrapped(result, Component.translatable("jane.cleanup.other_scroll_hint"), 0xAAAAAA);
        result.add(new Line(null, 0));
        addWrapped(result, Component.translatable("jane.cleanup.other_explain1"), 0xFFCC77);
        result.add(new Line(null, 0));
        addWrapped(result, Component.translatable("jane.cleanup.other_explain2"), 0xCCCCCC);
        result.add(new Line(null, 0));
        for (ClientCompatibilityReport.ExtraMod mod : other) {
            addWrapped(result, Component.literal(mod.displayName()), 0xFFFFFF);
            addWrapped(result, Component.literal(mod.filename()), 0xAAAAAA);
            result.add(new Line(null, 0));
        }
        return result;
    }

    private void addWrapped(List<Line> result, Component text, int color) {
        for (FormattedCharSequence line : font.split(text, Math.max(60, width - 44)))
            result.add(new Line(line, color));
    }

    private int contentHeight() { return lines().size() * LINE_HEIGHT; }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        Layout layout = Layout.forSize(width, height);
        List<Line> lines = lines();
        int total = lines.size() * LINE_HEIGHT;
        int maxScroll = layout.maxScroll(total);
        scroll = Math.max(0, Math.min(scroll, maxScroll));
        confirmButton.active = layout.readable() && stillCurrent.getAsBoolean() && scroll >= maxScroll;
        centered(graphics, title, 7, 0xFFFFFF);
        centered(graphics, Component.translatable("jane.cleanup.other_confirm_counts",
                other.size(), totalMoveCount), height <= 170 ? 20 : 25, 0xFFCC77);
        if (layout.visibleHeight() > 0) {
            graphics.fill(10, layout.contentTop(), width - 10, layout.contentBottom(), 0x88000000);
            graphics.enableScissor(12, layout.contentTop(), width - 12, layout.contentBottom());
            int y = layout.contentTop() - scroll;
            for (Line line : lines) {
                if (line.text() != null && y + LINE_HEIGHT > layout.contentTop()
                        && y < layout.contentBottom())
                    graphics.drawString(font, line.text(), 16, y + 1, line.color());
                y += LINE_HEIGHT;
            }
            graphics.disableScissor();
            drawScrollbar(graphics, layout, total);
        }
        if (!layout.readable())
            centered(graphics, Component.translatable("jane.cleanup.other_enlarge_window"),
                    Math.max(26, layout.contentTop()), 0xFF7777);
        else if (!stillCurrent.getAsBoolean())
            centered(graphics, Component.translatable("jane.cleanup.plan_changed"),
                    layout.confirmY() - 12, 0xFF7777);
        renderWidgets(graphics, mouseX, mouseY, partialTick);
    }

    private void drawScrollbar(GuiGraphics graphics, Layout layout, int contentHeight) {
        int view = layout.visibleHeight();
        if (view <= 0 || contentHeight <= view) return;
        int thumb = thumbHeight(view, contentHeight);
        int track = view - thumb;
        int thumbY = layout.contentTop() + scroll * track / (contentHeight - view);
        int x = width - 20;
        graphics.fill(x, layout.contentTop(), x + BAR_WIDTH, layout.contentBottom(), 0xFF444444);
        graphics.fill(x, thumbY, x + BAR_WIDTH, thumbY + thumb, 0xFFAAAAAA);
    }

    private static int thumbHeight(int view, int total) {
        return Math.min(view, Math.max(12, view * view / Math.max(1, total)));
    }

    private int scrollFromPointer(double mouseY, Layout layout, int total) {
        int view = layout.visibleHeight();
        int thumb = thumbHeight(view, total);
        int travel = view - thumb;
        if (travel <= 0) return 0;
        int position = Math.max(0, Math.min(travel,
                (int) Math.round(mouseY) - layout.contentTop() - grabOffset));
        return (int) Math.round(position * (double) layout.maxScroll(total) / travel);
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        Layout layout = Layout.forSize(width, height);
        if (mouseY < layout.contentTop() || mouseY >= layout.contentBottom())
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        scroll = Math.max(0, Math.min(layout.maxScroll(contentHeight()),
                scroll - (int) Math.round(scrollY * 24)));
        return true;
    }

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        Layout layout = Layout.forSize(width, height);
        int total = contentHeight();
        int view = layout.visibleHeight();
        int x = width - 20;
        if (button != 0 || total <= view || mouseX < x - 2 || mouseX >= x + BAR_WIDTH + 2
                || mouseY < layout.contentTop() || mouseY >= layout.contentBottom()) return false;
        int thumb = thumbHeight(view, total);
        int thumbY = layout.contentTop() + scroll * (view - thumb) / (total - view);
        grabOffset = mouseY >= thumbY && mouseY < thumbY + thumb
                ? (int) mouseY - thumbY : thumb / 2;
        dragging = true;
        scroll = scrollFromPointer(mouseY, layout, total);
        return true;
    }

    @Override public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (button == 0 && dragging) {
            scroll = scrollFromPointer(mouseY, Layout.forSize(width, height), contentHeight());
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dx, dy);
    }

    @Override public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && dragging) {
            dragging = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }
}
