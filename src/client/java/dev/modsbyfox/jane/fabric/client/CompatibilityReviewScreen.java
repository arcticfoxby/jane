package dev.modsbyfox.jane.fabric.client;

import dev.modsbyfox.jane.core.ClientCompatibilityReport;
import dev.modsbyfox.jane.core.ClientDisableBatch;
import dev.modsbyfox.jane.core.ClientDisablePlan;
import dev.modsbyfox.jane.fabric.JaneLog;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Review appears only after the server-required exact baseline has passed. */
final class CompatibilityReviewScreen extends Screen {
    private static final Logger LOGGER = LoggerFactory.getLogger("jane");
    private enum RowType { TEXT, EXPLICIT_HEADER, OTHER_HEADER, EXPLICIT, OTHER }
    private record Row(RowType type, int height, FormattedCharSequence text,
                       ClientCompatibilityReport.ExtraMod mod) { }

    private final Screen parent;
    private final PendingClientAction.CompatibilityReview action;
    private final Set<String> selected = new HashSet<>();
    private boolean explicitOpen = true;
    private boolean otherOpen;
    private boolean busy;
    private int scroll;
    private int declarationScroll;
    private Component notice;
    private Button disableButton;
    private Button directButton;

    CompatibilityReviewScreen(Screen parent, PendingClientAction.CompatibilityReview action) {
        super(Component.translatable("jane.compat.title"));
        this.parent = parent;
        this.action = action;
    }

    @Override protected void init() {
        int buttonWidth = Math.min(240, width - 36);
        int x = (width - buttonWidth) / 2;
        disableButton = addRenderableWidget(Button.builder(Component.translatable("jane.compat.disable"),
                button -> minecraft.setScreen(new ClientDisableConfirmScreen(this, selected.size())))
                .bounds(x, height - 76, buttonWidth, 20).build());
        directButton = addRenderableWidget(Button.builder(Component.translatable("jane.compat.direct_join"),
                button -> directJoin()).bounds(x, height - 52, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), button -> onClose())
                .bounds(x, height - 28, buttonWidth, 20).build());
    }

    private int left() { return Math.max(12, width / 2 - Math.min(260, (width - 32) / 2)); }
    private int right() { return width - left() - 8; }
    private int textWidth() { return Math.max(100, right() - left() - 10); }
    private CompatibilityReviewLayout layout() {
        return CompatibilityReviewLayout.forSize(height, contentHeight(declarationRows()));
    }
    private int top() { return layout().listTop(); }
    private int bottom() { return layout().listBottom(); }

    /** Fixed technical boundary: its coordinates never depend on the Mod-list scroll offset. */
    private List<Row> declarationRows() {
        List<Row> result = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            for (FormattedCharSequence line : font.split(Component.translatable("jane.compat.boundary" + i), textWidth()))
                result.add(new Row(RowType.TEXT, font.lineHeight, line, null));
            if (i < 4) result.add(new Row(RowType.TEXT, 2, null, null));
        }
        return result;
    }

    private List<Row> rows() {
        List<Row> result = new ArrayList<>();
        addText(result, "jane.compat.intro1");
        addText(result, "jane.compat.intro2");
        addText(result, "jane.compat.intro3");
        result.add(new Row(RowType.TEXT, 8, null, null));
        result.add(new Row(RowType.EXPLICIT_HEADER, 22, null, null));
        if (explicitOpen)
            for (var mod : action.report().explicitClientMods()) result.add(new Row(RowType.EXPLICIT, 29, null, mod));
        result.add(new Row(RowType.OTHER_HEADER, 22, null, null));
        if (otherOpen) {
            addText(result, "jane.compat.other_warning");
            for (var mod : action.report().otherExtraMods()) result.add(new Row(RowType.OTHER, 27, null, mod));
        }
        result.add(new Row(RowType.TEXT, 6, null, null));
        addText(result, "jane.compat.disable_hint");
        addText(result, "jane.compat.dependency_warning");
        return result;
    }

    private void addText(List<Row> rows, String key) {
        for (FormattedCharSequence line : font.split(Component.translatable(key), textWidth()))
            rows.add(new Row(RowType.TEXT, 12, line, null));
    }

    private int contentHeight(List<Row> rows) { return rows.stream().mapToInt(Row::height).sum(); }
    private void clamp(List<Row> rows) {
        scroll = Math.max(0, Math.min(scroll, Math.max(0, contentHeight(rows) - (bottom() - top()))));
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float tick) {
        renderBackground(graphics);
        disableButton.active = !busy && !selected.isEmpty();
        directButton.active = !busy;
        graphics.drawCenteredString(font, title, width / 2, 8, 0xFFFFFF);
        graphics.drawCenteredString(font, Component.translatable("jane.compat.required_pass"), width / 2, 25, 0xAAFFAA);
        CompatibilityReviewLayout layout = layout();
        declarationScroll = Math.max(0, Math.min(declarationScroll, layout.declarationScrollLimit()));
        int declarationY = layout.declarationTop() - declarationScroll;
        int left = left();
        int right = right();
        graphics.enableScissor(left, layout.declarationTop(), right + 8, layout.declarationBottom());
        for (Row line : declarationRows()) {
            if (line.text() != null && declarationY + line.height() > layout.declarationTop()
                    && declarationY < layout.declarationBottom())
                graphics.drawString(font, line.text(), left + 5, declarationY, 0xBBBBBB);
            declarationY += line.height();
        }
        graphics.disableScissor();
        if (layout.declarationScrollLimit() > 0) {
            int view = layout.declarationBottom() - layout.declarationTop();
            int thumb = Math.max(8, view * view / layout.declarationContentHeight());
            int thumbY = layout.declarationTop() + declarationScroll * (view - thumb)
                    / layout.declarationScrollLimit();
            graphics.fill(right + 3, layout.declarationTop(), right + 7, layout.declarationBottom(), 0xFF444444);
            graphics.fill(right + 3, thumbY, right + 7, thumbY + thumb, 0xFFAAAAAA);
        }
        graphics.fill(left, layout.declarationBottom() + 1, right, layout.declarationBottom() + 2, 0xFF666666);
        List<Row> rows = rows();
        clamp(rows);
        if (top() < bottom()) {
            graphics.enableScissor(left, top(), right + 8, bottom());
            int y = top() - scroll;
            for (Row row : rows) {
                if (y + row.height() > top() && y < bottom()) drawRow(graphics, row, left, right, y, mouseX, mouseY);
                y += row.height();
            }
            graphics.disableScissor();
        }
        int total = contentHeight(rows);
        int view = bottom() - top();
        if (view > 0 && total > view) {
            int thumb = Math.min(view, Math.max(12, view * view / total));
            int thumbY = top() + scroll * (view - thumb) / (total - view);
            graphics.fill(right + 3, top(), right + 7, bottom(), 0xFF444444);
            graphics.fill(right + 3, thumbY, right + 7, thumbY + thumb, 0xFFAAAAAA);
        }
        if (notice != null)
            graphics.drawCenteredString(font, notice, width / 2, height - 86, 0xFF7777);
        super.render(graphics, mouseX, mouseY, tick);
    }

    private void drawRow(GuiGraphics graphics, Row row, int left, int right, int y, int mouseX, int mouseY) {
        if (row.type() == RowType.TEXT) {
            if (row.text() != null) graphics.drawString(font, row.text(), left + 5, y + 1, 0xBBBBBB);
            return;
        }
        graphics.fill(left, y, right, y + row.height() - 2,
                mouseX >= left && mouseX < right && mouseY >= y && mouseY < y + row.height()
                        ? 0xAA444444 : 0x88000000);
        if (row.type() == RowType.EXPLICIT_HEADER || row.type() == RowType.OTHER_HEADER) {
            boolean explicit = row.type() == RowType.EXPLICIT_HEADER;
            String text = ((explicit ? explicitOpen : otherOpen) ? "▼ " : "▶ ")
                    + Component.translatable(explicit ? "jane.compat.explicit" : "jane.compat.other").getString();
            graphics.drawString(font, fit(text, right - left - 48), left + 5, y + 6, 0xFFFFFF);
            graphics.drawString(font, Integer.toString(explicit ? action.report().explicitClientMods().size()
                    : action.report().otherExtraMods().size()), right - 24, y + 6, 0xFFFFFF);
            return;
        }
        var mod = row.mod();
        boolean selectable = row.type() == RowType.EXPLICIT && mod.sha512() != null;
        int x = left + (selectable ? 24 : 6);
        if (selectable) graphics.drawString(font, selected.contains(mod.filename()) ? "☑" : "☐",
                left + 6, y + 7, 0xFFFFFF);
        graphics.drawString(font, fit(mod.displayName(), right - x - 6), x, y + 3, 0xFFFFFF);
        String detail = mod.version() + (row.type() == RowType.EXPLICIT ? "  ·  " + mod.filename() : "");
        if (row.type() == RowType.EXPLICIT && mod.sha512() == null)
            detail += "  ·  " + Component.translatable("jane.direct.extra_unverified").getString();
        graphics.drawString(font, fit(detail, right - x - 6), x, y + 16,
                row.type() == RowType.EXPLICIT && mod.sha512() == null ? 0xFF7777 : 0xCCCCCC);
    }

    private String fit(String text, int maxWidth) {
        if (font.width(text) <= maxWidth) return text;
        return font.plainSubstrByWidth(text, Math.max(0, maxWidth - font.width("…"))) + "…";
    }

    @Override public boolean mouseScrolled(double x, double y, double amount) {
        CompatibilityReviewLayout layout = layout();
        if (y >= layout.declarationTop() && y < layout.declarationBottom()) {
            declarationScroll -= (int) Math.round(amount * 18);
            declarationScroll = Math.max(0, Math.min(declarationScroll, layout.declarationScrollLimit()));
            return true;
        }
        if (y < top() || y >= bottom()) return super.mouseScrolled(x, y, amount);
        scroll -= (int) Math.round(amount * 24);
        clamp(rows());
        return true;
    }

    @Override public boolean mouseClicked(double x, double y, int button) {
        if (super.mouseClicked(x, y, button)) return true;
        if (button != 0 || y < top() || y >= bottom() || busy) return false;
        int left = left();
        int right = right();
        if (x < left || x >= right) return false;
        int at = top() - scroll;
        for (Row row : rows()) {
            if (y >= at && y < at + row.height()) {
                switch (row.type()) {
                    case EXPLICIT_HEADER -> explicitOpen = !explicitOpen;
                    case OTHER_HEADER -> otherOpen = !otherOpen;
                    case EXPLICIT -> {
                        if (row.mod().sha512() == null) {
                            notice = Component.translatable("jane.compat.local_file_error");
                            return true;
                        }
                        if (!selected.add(row.mod().filename())) selected.remove(row.mod().filename());
                    }
                    default -> { return false; }
                }
                clamp(rows());
                return true;
            }
            at += row.height();
        }
        return false;
    }

    void disableSelected() {
        if (busy || selected.isEmpty()) return;
        busy = true;
        notice = Component.translatable("jane.compat.preparing_disable");
        List<ClientCompatibilityReport.ExtraMod> chosen = action.report().explicitClientMods().stream()
                .filter(mod -> selected.contains(mod.filename())).toList();
        Path gameDir = FabricLoader.getInstance().getGameDir();
        CompletableFuture.supplyAsync(() -> {
            try {
                ClientDisablePlan plan = ClientDisablePlan.prepare(gameDir, action.context().serverId(), action.report(), chosen);
                ClientDisableBatch.write(gameDir, plan, ProcessHandle.current().pid());
                return plan;
            } catch (Exception exception) { throw new java.util.concurrent.CompletionException(exception); }
        }).whenComplete((plan, error) -> minecraft.execute(() -> {
            busy = false;
            if (error != null) {
                LOGGER.error("{}client disable plan failed", JaneLog.client(action.context().serverId()), error);
                notice = Component.translatable("jane.compat.local_file_error");
                return;
            }
            LOGGER.info("{}playerAction=DISABLE_SELECTED selected={} modIds={} restartRequired=true",
                    JaneLog.client(action.context().serverId()), plan.entries().size(),
                    plan.entries().stream().map(ClientDisablePlan.Entry::modId).toList());
            try {
                WindowsHelperLauncher.launch(minecraft, gameDir, "Jane client disable", "disable.bat",
                        "disabled", plan.serverId(), plan.timestamp());
            } catch (Exception exception) {
                LOGGER.error("{}client disable helper failed to launch", JaneLog.client(action.context().serverId()), exception);
                notice = Component.translatable("jane.compat.launch_failed");
            }
        }));
    }

    private void directJoin() {
        if (busy) return;
        boolean saved = JaneClient.recordEnvironmentAction("USER_SELECT_DIRECT_JOIN",
                action.context(), action.report());
        minecraft.setScreen(new DirectJoinConfirmScreen(this, new PendingClientAction.EnvironmentDecision(
                action.context(), action.report(), action.target(), !saved)));
    }

    @Override public void onClose() { minecraft.setScreen(parent); }
}
