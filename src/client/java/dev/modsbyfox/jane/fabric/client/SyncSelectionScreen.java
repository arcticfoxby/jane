package dev.modsbyfox.jane.fabric.client;

import dev.modsbyfox.jane.core.ClientInstallCategory;
import dev.modsbyfox.jane.core.Comparison;
import dev.modsbyfox.jane.core.JaneSyncSession;
import dev.modsbyfox.jane.core.SyncSelection;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** Installation choices live here so the main sync screen can retain its compact layout. */
final class SyncSelectionScreen extends Screen {
    private final SyncScreen parent;
    private final JaneSyncSession session;
    private final SyncSelection selection;
    private boolean requiredOpen;
    private boolean optionalOpen = true;
    private boolean matchedOpen;
    private boolean sourcesOpen;
    private int scroll;
    private int declarationScroll;
    private boolean draggingBar;
    private boolean draggingDeclarationBar;

    private enum RowKind { REQUIRED_HEADER, MATCHED_HEADER, OPTIONAL_HEADER,
        SOURCES_HEADER, TRUSTED_SOURCE, SERVER_SOURCE, NOTE, MOD }
    private record Row(RowKind kind, int height, Comparison.Result comparison,
                       FormattedCharSequence note, int count) { }

    SyncSelectionScreen(SyncScreen parent, JaneSyncSession session, SyncSelection selection) {
        super(Component.translatable("jane.select.screen_title"));
        this.parent = parent;
        this.session = session;
        this.selection = selection;
    }

    @Override protected void init() {
        clearWidgets();
        int buttonWidth = Math.min(150, width - 24);
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), button -> onClose())
                .bounds((width - buttonWidth) / 2, height - 24, buttonWidth, 20).build());
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        SelectionScreenLayout layout = SelectionScreenLayout.forSize(width, height);
        graphics.drawCenteredString(font, fit(title.getString(), width - 18), width / 2,
                layout.compact() ? 5 : 9, 0xFFFFFF);
        if (!layout.compact())
            graphics.drawCenteredString(font, fit(Component.translatable("jane.select.screen_hint").getString(),
                    width - 18), width / 2, 22, 0xBBBBBB);
        drawDeclaration(graphics, layout);
        List<Row> rows = rows(layout);
        clampScroll(rows, layout);
        JaneSyncSession.Snapshot snapshot = session.snapshot();
        graphics.enableScissor(layout.left(), layout.listTop(), layout.right() + 8, layout.listBottom());
        int y = layout.listTop() - scroll;
        for (Row row : rows) {
            if (y + row.height() > layout.listTop() && y < layout.listBottom())
                drawRow(graphics, snapshot, row, layout, y, mouseX, mouseY);
            y += row.height();
        }
        graphics.disableScissor();
        drawScrollbar(graphics, layout.right() + 3, layout.listTop(), layout.listBottom(),
                contentHeight(rows), scroll);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private List<FormattedCharSequence> declarationLines(SelectionScreenLayout layout) {
        int lineWidth = Math.max(20, layout.right() - layout.left() - 12);
        List<FormattedCharSequence> lines = new ArrayList<>();
        lines.addAll(font.split(Component.translatable("jane.select.declaration1"), lineWidth));
        lines.addAll(font.split(Component.translatable("jane.select.declaration2"), lineWidth));
        lines.addAll(font.split(Component.translatable("jane.select.declaration3"), lineWidth));
        return lines;
    }

    private void drawDeclaration(GuiGraphics graphics, SelectionScreenLayout layout) {
        int left = layout.left();
        int right = layout.right();
        graphics.fill(left, layout.declarationTop(), right, layout.declarationBottom(), 0x88000000);
        graphics.drawString(font, fit(Component.translatable("jane.select.declaration_title").getString(),
                right - left - 10), left + 5, layout.declarationTop() + 2, 0xFFCC77);
        List<FormattedCharSequence> lines = declarationLines(layout);
        int textTop = layout.declarationTop() + 12;
        int textBottom = layout.declarationBottom() - 1;
        int visible = textBottom - textTop;
        int total = lines.size() * 10;
        declarationScroll = clamp(declarationScroll, 0, Math.max(0, total - visible));
        graphics.enableScissor(left, textTop, right + 8, textBottom);
        int y = textTop - declarationScroll;
        for (FormattedCharSequence line : lines) {
            graphics.drawString(font, line, left + 5, y, 0xBBBBBB);
            y += 10;
        }
        graphics.disableScissor();
        drawScrollbar(graphics, right + 3, textTop, textBottom, total, declarationScroll);
    }

    private List<Row> rows(SelectionScreenLayout layout) {
        List<Row> rows = new ArrayList<>();
        List<Comparison.Result> comparisons = session.context().results();
        Map<String, ClientInstallCategory> categories = selection.categories();
        List<Comparison.Result> required = SelectionScreenRows.pending(
                comparisons, categories, ClientInstallCategory.SERVER_REQUIRED);
        List<Comparison.Result> optional = SelectionScreenRows.pending(
                comparisons, categories, ClientInstallCategory.CLIENT_OPTIONAL);
        List<Comparison.Result> matched = SelectionScreenRows.matched(comparisons);
        rows.add(new Row(RowKind.REQUIRED_HEADER, 22, null, null, required.size()));
        if (requiredOpen)
            for (Comparison.Result result : required) rows.add(new Row(RowKind.MOD, 42, result, null, 0));
        rows.add(new Row(RowKind.MATCHED_HEADER, 22, null, null, matched.size()));
        if (matchedOpen)
            for (Comparison.Result result : matched) rows.add(new Row(RowKind.MOD, 42, result, null, 0));
        rows.add(new Row(RowKind.OPTIONAL_HEADER, 22, null, null, optional.size()));
        if (optionalOpen) {
            for (Comparison.Result result : optional) rows.add(new Row(RowKind.MOD, 42, result, null, 0));
            if (optional.isEmpty()) addNote(rows, "jane.select.optional_empty", layout);
        }
        rows.add(new Row(RowKind.SOURCES_HEADER, 22, null, null, 0));
        if (sourcesOpen) {
            rows.add(new Row(RowKind.TRUSTED_SOURCE, 22, null, null, 0));
            rows.add(new Row(RowKind.SERVER_SOURCE, 22, null, null, 0));
            if (!parent.serverSourceAvailable())
                addNote(rows, "jane.sync.server_source_unavailable", layout);
        }
        return rows;
    }

    private void addNote(List<Row> rows, String key, SelectionScreenLayout layout) {
        for (FormattedCharSequence line : font.split(Component.translatable(key),
                Math.max(20, layout.right() - layout.left() - 12)))
            rows.add(new Row(RowKind.NOTE, 12, null, line, 0));
    }

    private int contentHeight(List<Row> rows) { return rows.stream().mapToInt(Row::height).sum(); }

    private void clampScroll(List<Row> rows, SelectionScreenLayout layout) {
        scroll = clamp(scroll, 0, Math.max(0, contentHeight(rows) - layout.listHeight()));
    }

    private void drawRow(GuiGraphics graphics, JaneSyncSession.Snapshot snapshot, Row row,
                         SelectionScreenLayout layout, int y, int mouseX, int mouseY) {
        int left = layout.left();
        int right = layout.right();
        if (row.kind() == RowKind.NOTE) {
            graphics.drawString(font, row.note(), left + 5, y + 1, 0xBBBBBB);
            return;
        }
        boolean hover = mouseX >= left && mouseX < right && mouseY >= y && mouseY < y + row.height();
        graphics.fill(left, y, right, y + row.height() - 2, hover ? 0xAA444444 : 0x88000000);
        if (row.kind() == RowKind.TRUSTED_SOURCE || row.kind() == RowKind.SERVER_SOURCE) {
            boolean server = row.kind() == RowKind.SERVER_SOURCE;
            boolean available = !server || parent.serverSourceAvailable();
            boolean enabled = server ? parent.serverSourceEnabled() : parent.trustedSourceEnabled();
            String name = Component.translatable(server ? "jane.sync.source_server"
                    : "jane.sync.source_trusted").getString();
            graphics.drawString(font, fit((enabled ? "☑ " : "☐ ") + name, right - left - 12),
                    left + 6, y + 6, available && parent.sourceSettingsEditable() ? 0xFFFFFF : 0x999999);
            return;
        }
        if (row.kind() != RowKind.MOD) {
            String key = switch (row.kind()) {
                case REQUIRED_HEADER -> "jane.select.required_group";
                case MATCHED_HEADER -> "jane.sync.matched_group";
                case OPTIONAL_HEADER -> "jane.select.optional_group";
                case SOURCES_HEADER -> "jane.sync.source_settings";
                default -> throw new IllegalStateException("Unknown row kind");
            };
            boolean open = switch (row.kind()) {
                case REQUIRED_HEADER -> requiredOpen;
                case MATCHED_HEADER -> matchedOpen;
                case OPTIONAL_HEADER -> optionalOpen;
                case SOURCES_HEADER -> sourcesOpen;
                default -> false;
            };
            String label = (open ? "▼ " : "▶ ") + Component.translatable(key).getString();
            if (row.kind() != RowKind.SOURCES_HEADER)
                label += Component.translatable(row.kind() == RowKind.REQUIRED_HEADER
                        ? "jane.sync.pending_count" : "jane.sync.group_count", row.count()).getString();
            graphics.drawString(font, fit(label, right - left - 10), left + 5, y + 6, 0xFFFFFF);
            return;
        }
        Comparison.Result result = row.comparison();
        String modId = result.required().modId();
        boolean matched = result.status() == Comparison.Status.OK;
        boolean selected = selection.isSelected(modId);
        boolean adjustable = SelectionScreenRows.adjustable(result, selection.category(modId));
        int textLeft = left + 25;
        graphics.drawString(font, matched ? "✓" : adjustable ? selected ? "☑" : "☐" : "•",
                left + 6, y + 13, matched ? 0xAAFFAA
                        : adjustable && parent.selectionEditable() ? 0xFFFFFF : 0x999999);
        graphics.drawString(font, fit(result.required().displayName(), right - textLeft - 8),
                textLeft, y + 3, 0xFFFFFF);
        Component status = Component.translatable("jane.status."
                + result.status().name().toLowerCase(Locale.ROOT));
        int statusWidth = Math.min(95, font.width(status) + 2);
        graphics.drawString(font, fit(modId + " · " + result.required().version(),
                right - textLeft - statusWidth - 10), textLeft, y + 15, 0xBBBBBB);
        graphics.drawString(font, fit(status.getString(), statusWidth), right - statusWidth - 5,
                y + 15, matched ? 0xAAFFAA : 0xFFCC77);
        String size = fit(String.format(Locale.ROOT, "%.1f MiB", result.required().fileSize() / 1048576.0),
                Math.max(30, (right - textLeft) / 2));
        graphics.drawString(font, fit(sourceLabel(snapshot, result),
                right - textLeft - font.width(size) - 12), textLeft, y + 28, 0xAAAAAA);
        graphics.drawString(font, size, right - 5 - font.width(size), y + 28, 0xAAAAAA);
    }

    private String sourceLabel(JaneSyncSession.Snapshot snapshot, Comparison.Result result) {
        if (result.status() == Comparison.Status.OK)
            return Component.translatable("jane.details.matched").getString();
        if (snapshot.resolution() == null)
            return Component.translatable("jane.details.source_pending").getString();
        for (JaneSyncSession.ItemState item : snapshot.items()) {
            if (!item.item().comparison().required().modId().equals(result.required().modId())) continue;
            if (item.state() == JaneSyncSession.RuntimeState.DOWNLOADING)
                return Component.translatable("jane.runtime.downloading", (int) (100 * item.downloadedBytes()
                        / Math.max(1, result.required().fileSize()))).getString();
            if (item.state() != null && item.state() != JaneSyncSession.RuntimeState.WAITING)
                return Component.translatable("jane.runtime." + item.state().name().toLowerCase(Locale.ROOT))
                        .getString();
            return Component.translatable(switch (item.item().availability()) {
                case TRUSTED_AVAILABLE -> "jane.details.trusted_source";
                case SERVER_ONLY -> "jane.details.current_server";
                case ALREADY_PRESENT -> "jane.details.matched";
                case LOOKUP_FAILED -> "jane.sync.lookup_failed";
                case UNRESOLVED -> "jane.details.unknown_source";
                case LOCAL_ERROR -> "jane.sync.local_error";
            }).getString();
        }
        return Component.translatable("jane.details.source_pending").getString();
    }

    private String fit(String value, int maxWidth) {
        if (maxWidth <= 0) return "";
        if (font.width(value) <= maxWidth) return value;
        return font.plainSubstrByWidth(value, Math.max(0, maxWidth - font.width("…"))) + "…";
    }

    private void drawScrollbar(GuiGraphics graphics, int x, int top, int bottom, int total, int scrollValue) {
        int visible = bottom - top;
        if (visible <= 0 || total <= visible) return;
        int thumb = Math.min(visible, Math.max(8, visible * visible / total));
        int thumbY = top + scrollValue * (visible - thumb) / (total - visible);
        graphics.fill(x, top, x + 4, bottom, 0xFF444444);
        graphics.fill(x, thumbY, x + 4, thumbY + thumb, 0xFFAAAAAA);
    }

    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(value, max)); }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        SelectionScreenLayout layout = SelectionScreenLayout.forSize(width, height);
        if (mouseY >= layout.declarationTop() && mouseY < layout.declarationBottom()) {
            int visible = layout.declarationBottom() - layout.declarationTop() - 13;
            declarationScroll = clamp(declarationScroll - (int) Math.round(amount * 18), 0,
                    Math.max(0, declarationLines(layout).size() * 10 - visible));
            return true;
        }
        if (mouseY >= layout.listTop() && mouseY < layout.listBottom()) {
            List<Row> rows = rows(layout);
            scroll = clamp(scroll - (int) Math.round(amount * 24), 0,
                    Math.max(0, contentHeight(rows) - layout.listHeight()));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (button != 0) return false;
        SelectionScreenLayout layout = SelectionScreenLayout.forSize(width, height);
        if (mouseY >= layout.declarationTop() && mouseY < layout.declarationBottom()
                && mouseX >= layout.right() + 3 && mouseX <= layout.right() + 8) {
            draggingDeclarationBar = true;
            return true;
        }
        if (mouseY < layout.listTop() || mouseY >= layout.listBottom()) return false;
        List<Row> rows = rows(layout);
        if (mouseX >= layout.right() + 3 && mouseX <= layout.right() + 9
                && contentHeight(rows) > layout.listHeight()) {
            draggingBar = true;
            return true;
        }
        if (mouseX < layout.left() || mouseX >= layout.right()) return false;
        int y = layout.listTop() - scroll;
        for (Row row : rows) {
            if (mouseY >= y && mouseY < y + row.height()) {
                switch (row.kind()) {
                    case REQUIRED_HEADER -> requiredOpen = !requiredOpen;
                    case MATCHED_HEADER -> matchedOpen = !matchedOpen;
                    case OPTIONAL_HEADER -> optionalOpen = !optionalOpen;
                    case SOURCES_HEADER -> sourcesOpen = !sourcesOpen;
                    case TRUSTED_SOURCE -> parent.toggleSource(false);
                    case SERVER_SOURCE -> parent.toggleSource(true);
                    case MOD -> {
                        if (SelectionScreenRows.adjustable(row.comparison(),
                                selection.category(row.comparison().required().modId())))
                            parent.toggleSelection(row.comparison());
                    }
                    case NOTE -> { return false; }
                }
                clampScroll(rows(layout), layout);
                return true;
            }
            y += row.height();
        }
        return false;
    }

    @Override public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingDeclarationBar && button == 0) {
            SelectionScreenLayout layout = SelectionScreenLayout.forSize(width, height);
            int visible = layout.declarationBottom() - layout.declarationTop() - 13;
            int total = declarationLines(layout).size() * 10;
            declarationScroll = clamp(declarationScroll + (int) Math.round(dragY * total / Math.max(1, visible)),
                    0, Math.max(0, total - visible));
            return true;
        }
        if (draggingBar && button == 0) {
            SelectionScreenLayout layout = SelectionScreenLayout.forSize(width, height);
            List<Row> rows = rows(layout);
            int total = contentHeight(rows);
            int visible = layout.listHeight();
            int thumb = Math.max(8, visible * visible / Math.max(1, total));
            scroll = clamp(scroll + (int) Math.round(dragY * (total - visible)
                    / Math.max(1, visible - thumb)), 0, Math.max(0, total - visible));
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override public boolean mouseReleased(double mouseX, double mouseY, int button) {
        draggingBar = false;
        draggingDeclarationBar = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override public void onClose() { minecraft.setScreen(parent); }
}

/** Coordinates remain testable without constructing a Minecraft Screen. */
record SelectionScreenLayout(int left, int right, int declarationTop, int declarationBottom,
                             int listTop, int listBottom, boolean compact) {
    static SelectionScreenLayout forSize(int width, int height) {
        boolean compact = width < 300 || height <= 190;
        int left = Math.max(8, width / 2 - Math.min(270, (width - 26) / 2));
        int declarationTop = compact ? 19 : 34;
        int declarationBottom = compact ? 43 : 75;
        int listTop = declarationBottom + 3;
        return new SelectionScreenLayout(left, width - left - 8,
                declarationTop, declarationBottom, listTop, height - 29, compact);
    }
    int listHeight() { return listBottom - listTop; }
}

/** Stable manifest order for the secondary selection groups. */
final class SelectionScreenRows {
    private SelectionScreenRows() { }
    static boolean adjustable(Comparison.Result result, ClientInstallCategory category) {
        return result.status() != Comparison.Status.OK
                && result.status() != Comparison.Status.FILE_ERROR
                && category == ClientInstallCategory.CLIENT_OPTIONAL;
    }
    static List<Comparison.Result> pending(List<Comparison.Result> comparisons,
                                           Map<String, ClientInstallCategory> categories,
                                           ClientInstallCategory category) {
        return comparisons.stream().filter(result -> result.status() != Comparison.Status.OK
                && categories.get(result.required().modId()) == category
                && (category != ClientInstallCategory.CLIENT_OPTIONAL
                || result.status() != Comparison.Status.FILE_ERROR)).toList();
    }
    static List<Comparison.Result> matched(List<Comparison.Result> comparisons) {
        return comparisons.stream().filter(result -> result.status() == Comparison.Status.OK).toList();
    }
}
