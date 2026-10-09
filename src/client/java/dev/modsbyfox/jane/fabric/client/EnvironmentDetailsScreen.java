package dev.modsbyfox.jane.fabric.client;

import dev.modsbyfox.jane.core.ClientCompatibilityReport;
import dev.modsbyfox.jane.core.Comparison;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Scrollable local comparison details, deliberately independent of source resolution. */
final class EnvironmentDetailsScreen extends Screen {
    private enum Group { UNMET, MATCHED, EXTRAS }
    private record Row(Group group, Comparison.Result result, ClientCompatibilityReport.ExtraMod extra, int height) { }

    private final Screen parent;
    private final PendingClientAction.EnvironmentDecision action;
    private boolean unmetOpen = true;
    private boolean matchedOpen;
    private boolean extrasOpen = true;
    private int scroll;

    EnvironmentDetailsScreen(Screen parent, PendingClientAction.EnvironmentDecision action) {
        super(Component.translatable("jane.environment.details_title"));
        this.parent = parent;
        this.action = action;
    }

    @Override protected void init() {
        int buttonWidth = Math.min(160, width - 24);
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), button -> onClose())
                .bounds((width - buttonWidth) / 2, height - 27, buttonWidth, 20).build());
    }

    private int left() { return Math.max(10, width / 2 - Math.min(270, (width - 28) / 2)); }
    private int right() { return width - left() - 10; }
    private int top() { return 31; }
    private int bottom() { return Math.max(top() + 1, height - 34); }

    private List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        rows.add(new Row(Group.UNMET, null, null, 22));
        if (unmetOpen) for (Comparison.Result result : action.context().results())
            if (result.status() != Comparison.Status.OK) rows.add(new Row(Group.UNMET, result, null, 34));
        rows.add(new Row(Group.MATCHED, null, null, 22));
        if (matchedOpen) for (Comparison.Result result : action.context().results())
            if (result.status() == Comparison.Status.OK) rows.add(new Row(Group.MATCHED, result, null, 30));
        rows.add(new Row(Group.EXTRAS, null, null, 22));
        if (extrasOpen) {
            for (ClientCompatibilityReport.ExtraMod extra : action.report().explicitClientMods())
                rows.add(new Row(Group.EXTRAS, null, extra, 30));
            for (ClientCompatibilityReport.ExtraMod extra : action.report().otherExtraMods())
                rows.add(new Row(Group.EXTRAS, null, extra, 30));
        }
        return rows;
    }

    private int count(Group group) {
        return switch (group) {
            case UNMET -> (int) action.context().results().stream()
                    .filter(result -> result.status() != Comparison.Status.OK).count();
            case MATCHED -> (int) action.context().results().stream()
                    .filter(result -> result.status() == Comparison.Status.OK).count();
            case EXTRAS -> action.report().explicitClientMods().size() + action.report().otherExtraMods().size();
        };
    }

    private boolean open(Group group) {
        return switch (group) { case UNMET -> unmetOpen; case MATCHED -> matchedOpen; case EXTRAS -> extrasOpen; };
    }

    private void toggle(Group group) {
        switch (group) {
            case UNMET -> unmetOpen = !unmetOpen;
            case MATCHED -> matchedOpen = !matchedOpen;
            case EXTRAS -> extrasOpen = !extrasOpen;
        }
    }

    private int contentHeight(List<Row> rows) { return rows.stream().mapToInt(Row::height).sum(); }
    private void clamp(List<Row> rows) {
        scroll = Math.max(0, Math.min(scroll, Math.max(0, contentHeight(rows) - (bottom() - top()))));
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, 9, 0xFFFFFF);
        List<Row> rows = rows();
        clamp(rows);
        int y = top() - scroll;
        graphics.enableScissor(left(), top(), right() + 8, bottom());
        for (Row row : rows) {
            if (y + row.height() > top() && y < bottom()) drawRow(graphics, row, y, mouseX, mouseY);
            y += row.height();
        }
        graphics.disableScissor();
        drawScrollbar(graphics, contentHeight(rows));
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void drawRow(GuiGraphics graphics, Row row, int y, int mouseX, int mouseY) {
        int left = left();
        int right = right();
        if (row.result() == null && row.extra() == null) {
            boolean hovered = mouseX >= left && mouseX < right && mouseY >= y && mouseY < y + row.height();
            graphics.fill(left, y, right, y + row.height() - 2, hovered ? 0xFF555555 : 0xFF333333);
            String key = switch (row.group()) {
                case UNMET -> "jane.environment.unmet_group";
                case MATCHED -> "jane.environment.matched_group";
                case EXTRAS -> "jane.environment.extra_group";
            };
            graphics.drawString(font, fit((open(row.group()) ? "▼ " : "▶ ")
                    + Component.translatable(key).getString(), right - left - 55), left + 5, y + 6, 0xFFFFFF);
            graphics.drawString(font, Integer.toString(count(row.group())), right - 24, y + 6, 0xFFFFFF);
            return;
        }
        graphics.fill(left, y, right, y + row.height() - 2, 0x88000000);
        if (row.result() != null) {
            var result = row.result();
            graphics.drawString(font, fit(result.required().displayName(), right - left - 12), left + 6, y + 3, 0xFFFFFF);
            String status = !result.retainedCandidates().isEmpty() && result.status() != Comparison.Status.OK
                    ? Component.translatable("jane.environment.unsafe_duplicate").getString()
                    : Component.translatable("jane.status."
                    + result.status().name().toLowerCase(Locale.ROOT)).getString();
            String detail = result.required().modId() + " · " + result.required().version() + " · " + status;
            graphics.drawString(font, fit(detail, right - left - 12), left + 6, y + 17,
                    result.status() == Comparison.Status.FILE_ERROR ? 0xFF7777 : 0xFFCC77);
        } else {
            var extra = row.extra();
            graphics.drawString(font, fit(extra.displayName(), right - left - 12), left + 6, y + 3, 0xFFFFFF);
            String kind = Component.translatable(extra.kind() == ClientCompatibilityReport.Kind.EXPLICIT_CLIENT
                    ? "jane.compat.explicit" : "jane.compat.other").getString();
            graphics.drawString(font, fit(extra.modId() + " · " + extra.version() + " · " + kind,
                    right - left - 12), left + 6, y + 16, 0xCCCCCC);
        }
    }

    private String fit(String value, int maxWidth) {
        if (font.width(value) <= maxWidth) return value;
        return font.plainSubstrByWidth(value, Math.max(0, maxWidth - font.width("…"))) + "…";
    }

    private void drawScrollbar(GuiGraphics graphics, int total) {
        int view = bottom() - top();
        if (total <= view) return;
        int thumb = Math.max(12, view * view / total);
        int thumbY = top() + scroll * (view - thumb) / (total - view);
        graphics.fill(right() + 3, top(), right() + 7, bottom(), 0xFF444444);
        graphics.fill(right() + 3, thumbY, right() + 7, thumbY + thumb, 0xFFAAAAAA);
    }

    @Override public boolean mouseScrolled(double x, double y, double amount) {
        if (y < top() || y >= bottom()) return super.mouseScrolled(x, y, amount);
        scroll -= (int) Math.round(amount * 24);
        clamp(rows());
        return true;
    }

    @Override public boolean mouseClicked(double x, double y, int button) {
        if (super.mouseClicked(x, y, button)) return true;
        if (button != 0 || x < left() || x >= right() || y < top() || y >= bottom()) return false;
        int at = top() - scroll;
        for (Row row : rows()) {
            if (y >= at && y < at + row.height()) {
                if (row.result() == null && row.extra() == null) {
                    toggle(row.group());
                    clamp(rows());
                    return true;
                }
                return false;
            }
            at += row.height();
        }
        return false;
    }

    @Override public void onClose() { minecraft.setScreen(parent); }
}
