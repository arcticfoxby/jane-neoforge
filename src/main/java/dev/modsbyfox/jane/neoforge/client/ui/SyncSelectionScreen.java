package dev.modsbyfox.jane.neoforge.client.ui;

import dev.modsbyfox.jane.core.ClientInstallCategory;
import dev.modsbyfox.jane.core.ClientCleanupGraph;
import dev.modsbyfox.jane.core.ClientCompatibilityReport;
import dev.modsbyfox.jane.core.Comparison;
import dev.modsbyfox.jane.core.ResolutionPlan;
import dev.modsbyfox.jane.core.SyncSelection;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** Manifest-order selection; only uncertain, healthy candidates can be toggled. */
final class SyncSelectionScreen extends UiScreen {
    record Layout(int left, int right, int declarationTop, int declarationBottom,
                  int listTop, int listBottom) {
        static Layout forSize(int width, int height) {
            boolean compact = width < 300 || height < 190;
            int left = Math.max(8, width / 2 - Math.min(270, (width - 26) / 2));
            int declarationTop = compact ? 20 : 34;
            int declarationBottom = compact ? 44 : 75;
            return new Layout(left, width - left - 8, declarationTop, declarationBottom,
                    declarationBottom + 3, height - 29);
        }
        int listHeight() { return Math.max(0, listBottom - listTop); }
        int maxScroll(int contentHeight) { return Math.max(0, contentHeight - listHeight()); }
    }

    private enum Kind { SOURCE_TRUSTED, SOURCE_SERVER, REQUIRED_HEADER, OPTIONAL_HEADER,
        MATCHED_HEADER, EXTRA_SERVER_HEADER, EXTRA_SERVER_MOD, MOD }
    private record Row(Kind kind, Comparison.Result result,
                       ClientCompatibilityReport.ExtraMod extra, int height) {
        Row(Kind kind, Comparison.Result result, int height) { this(kind, result, null, height); }
    }

    private final SyncScreen owner;
    private final SyncSelection selection;
    private final List<ClientCompatibilityReport.ExtraMod> declaredServerOnly;
    private int scroll;

    SyncSelectionScreen(SyncScreen owner) {
        super(Component.translatable("jane.select.screen_title"), owner);
        this.owner = owner;
        this.selection = owner.selection();
        this.declaredServerOnly = owner.extras().declaredServerOnlyMods();
    }

    static boolean adjustable(Comparison.Result result, ClientInstallCategory category) {
        return result.status() != Comparison.Status.OK && result.status() != Comparison.Status.FILE_ERROR
                && result.retainedCandidates().isEmpty() && category == ClientInstallCategory.CLIENT_OPTIONAL;
    }

    private List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        rows.add(new Row(Kind.SOURCE_TRUSTED, null, 22));
        rows.add(new Row(Kind.SOURCE_SERVER, null, 22));
        if (!declaredServerOnly.isEmpty()) {
            rows.add(new Row(Kind.EXTRA_SERVER_HEADER, null, 21));
            for (ClientCompatibilityReport.ExtraMod extra : declaredServerOnly)
                rows.add(new Row(Kind.EXTRA_SERVER_MOD, null, extra, 50));
        }
        rows.add(new Row(Kind.REQUIRED_HEADER, null, 21));
        for (Comparison.Result result : owner.session().context().results())
            if (result.status() != Comparison.Status.OK
                    && selection.category(result.required().modId()) == ClientInstallCategory.SERVER_REQUIRED)
                rows.add(new Row(Kind.MOD, result, 34));
        rows.add(new Row(Kind.OPTIONAL_HEADER, null, 21));
        for (Comparison.Result result : owner.session().context().results())
            if (result.status() != Comparison.Status.OK
                    && selection.category(result.required().modId()) == ClientInstallCategory.CLIENT_OPTIONAL)
                rows.add(new Row(Kind.MOD, result, 34));
        rows.add(new Row(Kind.MATCHED_HEADER, null, 21));
        for (Comparison.Result result : owner.session().context().results())
            if (result.status() == Comparison.Status.OK) rows.add(new Row(Kind.MOD, result, 34));
        return rows;
    }

    private int totalHeight(List<Row> rows) { return rows.stream().mapToInt(Row::height).sum(); }

    @Override protected void init() {
        clearWidgets();
        int w = Math.min(150, buttonWidth());
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), button -> onClose())
                .bounds((width - w) / 2, height - 24, w, 20).build());
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        Layout layout = Layout.forSize(width, height);
        centered(graphics, title, 7, 0xFFFFFF);
        graphics.fill(layout.left(), layout.declarationTop(), layout.right(),
                layout.declarationBottom(), 0x88000000);
        graphics.drawString(font, fit(Component.translatable("jane.select.declaration_title"),
                layout.right() - layout.left() - 10), layout.left() + 5,
                layout.declarationTop() + 2, 0xFFCC77);
        graphics.drawString(font, fit(Component.translatable("jane.select.declaration2"),
                layout.right() - layout.left() - 10), layout.left() + 5,
                layout.declarationTop() + 13, 0xBBBBBB);
        List<Row> rows = rows();
        scroll = Math.min(scroll, layout.maxScroll(totalHeight(rows)));
        if (layout.listHeight() > 0) {
            graphics.enableScissor(layout.left(), layout.listTop(), layout.right(), layout.listBottom());
            int y = layout.listTop() - scroll;
            for (Row row : rows) {
                if (y + row.height() > layout.listTop() && y < layout.listBottom())
                    drawRow(graphics, row, layout, y, mouseX, mouseY);
                y += row.height();
            }
            graphics.disableScissor();
        }
        renderWidgets(graphics, mouseX, mouseY, partialTick);
    }

    private void drawRow(GuiGraphics graphics, Row row, Layout layout, int y, int mouseX, int mouseY) {
        boolean hovered = mouseX >= layout.left() && mouseX < layout.right()
                && mouseY >= y && mouseY < y + row.height();
        graphics.fill(layout.left(), y, layout.right(), y + row.height() - 2,
                hovered ? 0xAA444444 : 0x88000000);
        int left = layout.left() + 6;
        int available = layout.right() - left - 5;
        if (row.kind() == Kind.SOURCE_TRUSTED || row.kind() == Kind.SOURCE_SERVER) {
            boolean server = row.kind() == Kind.SOURCE_SERVER;
            boolean checked = server ? owner.serverEnabled() : owner.trustedEnabled();
            boolean enabled = owner.selectionEditable() && (!server || owner.serverAvailable());
            Component name = Component.translatable(server ? "jane.sync.source_server" : "jane.sync.source_trusted");
            graphics.drawString(font, fit((checked ? "☑ " : "☐ ") + name.getString(), available),
                    left, y + 6, enabled ? 0xFFFFFF : 0x999999);
            return;
        }
        if (row.kind() == Kind.EXTRA_SERVER_MOD) {
            ClientCompatibilityReport.ExtraMod extra = row.extra();
            boolean checked = owner.cleanupSelection().isSelected(extra.filename());
            ClientCleanupGraph.Decision decision = owner.cleanupSelection().serverDecision(extra.filename());
            boolean canToggle = owner.cleanupSelectionEditable() && extra.verified()
                    && (checked || decision != null && decision.eligible());
            graphics.drawString(font, checked ? "☑" : "☐", left, y + 16,
                    canToggle ? 0xFFFFFF : 0x777777);
            int detailLeft = left + 17;
            int detailWidth = available - 17;
            graphics.drawString(font, fit(extra.displayName(), detailWidth), detailLeft, y + 2, 0xFFFFFF);
            graphics.drawString(font, fit(extra.modId() + " · " + extra.version(), detailWidth),
                    detailLeft, y + 13, 0xCCCCCC);
            graphics.drawString(font, fit(extra.filename(), detailWidth), detailLeft, y + 24, 0xFFCC77);
            graphics.drawString(font, fit(CleanupLabels.status(extra, decision, checked), detailWidth),
                    detailLeft, y + 35, canToggle ? 0x88CCFF : 0xFF7777);
            return;
        }
        if (row.kind() != Kind.MOD) {
            String key = switch (row.kind()) {
                case REQUIRED_HEADER -> "jane.select.required_group";
                case OPTIONAL_HEADER -> "jane.select.optional_group";
                case MATCHED_HEADER -> "jane.sync.matched_group";
                case EXTRA_SERVER_HEADER -> "jane.select.extra_server_group";
                default -> throw new IllegalStateException("Unknown selection row");
            };
            Component heading = row.kind() == Kind.EXTRA_SERVER_HEADER
                    ? Component.translatable(key, declaredServerOnly.size(), owner.cleanupSelection().selectedServerCount())
                    : Component.translatable(key);
            graphics.drawString(font, fit(heading, available), left, y + 6,
                    row.kind() == Kind.EXTRA_SERVER_HEADER ? 0x66DDFF : 0xFFCC77);
            return;
        }
        Comparison.Result result = row.result();
        String id = result.required().modId();
        boolean canToggle = adjustable(result, selection.category(id)) && owner.selectionEditable();
        boolean checked = result.status() == Comparison.Status.OK || selection.isSelected(id);
        graphics.drawString(font, result.status() == Comparison.Status.OK ? "✓" : checked ? "☑" : "☐",
                left, y + 10, canToggle ? 0xFFFFFF : 0x999999);
        int textLeft = left + 17;
        graphics.drawString(font, fit(result.required().displayName(), layout.right() - textLeft - 5),
                textLeft, y + 2, 0xFFFFFF);
        Component status = Component.translatable("jane.status."
                + result.status().name().toLowerCase(Locale.ROOT));
        graphics.drawString(font, fit(id + " · " + status.getString(), layout.right() - textLeft - 5),
                textLeft, y + 17, result.status() == Comparison.Status.FILE_ERROR ? 0xFF7777 : 0xBBBBBB);
    }

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (button != 0) return false;
        Layout layout = Layout.forSize(width, height);
        if (mouseX < layout.left() || mouseX >= layout.right()
                || mouseY < layout.listTop() || mouseY >= layout.listBottom()) return false;
        int y = layout.listTop() - scroll;
        for (Row row : rows()) {
            if (mouseY >= y && mouseY < y + row.height()) {
                if (row.kind() == Kind.SOURCE_TRUSTED)
                    owner.setSourceEnabled(ResolutionPlan.TransferRoute.TRUSTED_SOURCE, !owner.trustedEnabled());
                else if (row.kind() == Kind.SOURCE_SERVER)
                    owner.setSourceEnabled(ResolutionPlan.TransferRoute.CURRENT_SERVER, !owner.serverEnabled());
                else if (row.kind() == Kind.EXTRA_SERVER_MOD && owner.cleanupSelectionEditable()) {
                    ClientCompatibilityReport.ExtraMod extra = row.extra();
                    boolean wanted = !owner.cleanupSelection().isSelected(extra.filename());
                    if (owner.cleanupSelection().setSelected(extra.filename(), wanted))
                        owner.cleanupSelectionChanged(extra.filename(), wanted);
                }
                else if (row.kind() == Kind.MOD && owner.selectionEditable()) {
                    Comparison.Result result = row.result();
                    String id = result.required().modId();
                    if (adjustable(result, selection.category(id))) {
                        selection.setSelected(id, !selection.isSelected(id));
                        owner.selectionChanged(id, selection.isSelected(id));
                    }
                }
                return true;
            }
            y += row.height();
        }
        return false;
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        Layout layout = Layout.forSize(width, height);
        int limit = layout.maxScroll(totalHeight(rows()));
        scroll = Math.max(0, Math.min(limit, scroll - (int) Math.round(scrollY * 24)));
        return true;
    }
}
