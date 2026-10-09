package dev.modsbyfox.jane.fabric.client;

import dev.modsbyfox.jane.core.ClientCompatibilityReport;
import dev.modsbyfox.jane.core.Comparison;
import dev.modsbyfox.jane.core.OneShotJoinOverride;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** The only direct-join confirmation; statements and local risks remain visible without source lookups. */
final class DirectJoinConfirmScreen extends Screen {
    private enum Group { UNMET, EXTRAS }
    private record StatementLine(FormattedCharSequence text, int height) { }
    private record RiskRow(Group group, Comparison.Result unmet,
                           ClientCompatibilityReport.ExtraMod extra, int height) { }

    private final Screen parent;
    private final PendingClientAction.EnvironmentDecision action;
    private int statementScroll;
    private int listScroll;
    private boolean audited;
    private boolean warningShown;
    private boolean joining;
    private Component notice;
    private Button confirmButton;

    DirectJoinConfirmScreen(Screen parent, PendingClientAction.EnvironmentDecision action) {
        super(Component.translatable("jane.direct.title"));
        this.parent = parent;
        this.action = action;
    }

    @Override protected void init() {
        clearWidgets();
        int buttonWidth = Math.max(1, Math.min(250, width - 24));
        int x = (width - buttonWidth) / 2;
        confirmButton = addRenderableWidget(Button.builder(Component.translatable("jane.direct.confirm"),
                button -> confirm()).bounds(x, height < 140 ? 15 : 23, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("jane.direct.back_sync"),
                button -> onClose()).bounds(x, backY(), buttonWidth, 20).build());
    }

    private boolean requiredPassed() { return Comparison.passed(action.context().results()); }
    private boolean mayJoin() {
        return action.target() != null && (requiredPassed()
                || OneShotJoinOverride.overrideable(action.context().manifest(), action.context().results()));
    }

    private int left() { return Math.max(10, width / 2 - Math.min(270, (width - 28) / 2)); }
    private int right() { return width - left() - 10; }
    private int statementTop() { return height < 140 ? 47 : 59; }
    private int statementViewport() {
        // Keep a useful list viewport even when three wrapped paragraphs occupy many lines.
        int reserved = 20 + 6 + Math.max(25, Math.min(100, height / 3)) + 8;
        int minimum = height < 140 ? 8 : 18;
        return Math.max(minimum, Math.min(statementHeight(),
                Math.max(minimum, height - statementTop() - reserved)));
    }
    private int backY() { return statementTop() + statementViewport() + 4; }
    private int listTop() { return backY() + 26; }
    private int listBottom() { return Math.max(0, height - 8); }

    private List<StatementLine> statements() {
        List<StatementLine> lines = new ArrayList<>();
        int width = Math.max(90, right() - left() - 12);
        for (int paragraph = 1; paragraph <= 3; paragraph++) {
            for (FormattedCharSequence line : font.split(Component.translatable("jane.direct.explain" + paragraph), width))
                lines.add(new StatementLine(line, 12));
            lines.add(new StatementLine(null, 6));
        }
        return lines;
    }

    private int statementHeight() { return statements().stream().mapToInt(StatementLine::height).sum(); }
    private List<RiskRow> risks() {
        List<RiskRow> rows = new ArrayList<>();
        List<Comparison.Result> unmet = action.context().results().stream()
                .filter(result -> result.status() != Comparison.Status.OK).toList();
        if (!unmet.isEmpty()) {
            rows.add(new RiskRow(Group.UNMET, null, null, 22));
            for (Comparison.Result result : unmet) rows.add(new RiskRow(Group.UNMET, result, null, 43));
        }
        if (!action.report().explicitClientMods().isEmpty() || !action.report().otherExtraMods().isEmpty()) {
            rows.add(new RiskRow(Group.EXTRAS, null, null, 22));
            for (ClientCompatibilityReport.ExtraMod extra : action.report().explicitClientMods())
                rows.add(new RiskRow(Group.EXTRAS, null, extra, 43));
            for (ClientCompatibilityReport.ExtraMod extra : action.report().otherExtraMods())
                rows.add(new RiskRow(Group.EXTRAS, null, extra, 43));
        }
        return rows;
    }

    private int riskHeight(List<RiskRow> rows) { return rows.stream().mapToInt(RiskRow::height).sum(); }
    private void clamp(List<RiskRow> rows) {
        statementScroll = Math.max(0, Math.min(statementScroll,
                Math.max(0, statementHeight() - statementViewport())));
        listScroll = Math.max(0, Math.min(listScroll,
                Math.max(0, riskHeight(rows) - Math.max(0, listBottom() - listTop()))));
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        confirmButton.active = !joining && mayJoin();
        graphics.drawCenteredString(font, fit(title.getString(), width - 20), width / 2,
                height < 140 ? 3 : 8, 0xFFFFFF);
        if (notice != null || auditIncomplete()) {
            Component warning = notice != null ? notice : Component.translatable("jane.select.audit_incomplete");
            graphics.drawCenteredString(font, fit(warning.getString(), width - 20), width / 2,
                    height < 140 ? 37 : 47, 0xFF7777);
        }
        List<RiskRow> rows = risks();
        clamp(rows);
        drawStatements(graphics);
        drawRisks(graphics, rows);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private boolean auditIncomplete() {
        return action.auditSaveFailed() || parent instanceof EnvironmentDecisionScreen decision && decision.auditIncomplete();
    }

    private void drawStatements(GuiGraphics graphics) {
        int y = statementTop() - statementScroll;
        graphics.enableScissor(left(), statementTop(), right() + 8, statementTop() + statementViewport());
        for (StatementLine line : statements()) {
            if (line.text() != null && y + line.height() > statementTop()
                    && y < statementTop() + statementViewport())
                graphics.drawString(font, line.text(), left() + 5, y + 1, 0xCCCCCC);
            y += line.height();
        }
        graphics.disableScissor();
        drawScrollbar(graphics, right() + 3, statementTop(), statementViewport(), statementHeight(), statementScroll);
    }

    private void drawRisks(GuiGraphics graphics, List<RiskRow> rows) {
        if (listBottom() <= listTop()) return;
        int y = listTop() - listScroll;
        graphics.enableScissor(left(), listTop(), right() + 8, listBottom());
        for (RiskRow row : rows) {
            if (y + row.height() > listTop() && y < listBottom()) drawRisk(graphics, row, y);
            y += row.height();
        }
        graphics.disableScissor();
        drawScrollbar(graphics, right() + 3, listTop(), listBottom() - listTop(), riskHeight(rows), listScroll);
    }

    private void drawRisk(GuiGraphics graphics, RiskRow row, int y) {
        graphics.fill(left(), y, right(), y + row.height() - 2,
                row.unmet() == null && row.extra() == null ? 0xFF333333 : 0x88000000);
        if (row.unmet() == null && row.extra() == null) {
            String key = row.group() == Group.UNMET ? "jane.direct.unmet_group" : "jane.direct.extra_group";
            int count = row.group() == Group.UNMET
                    ? (int) action.context().results().stream().filter(result -> result.status() != Comparison.Status.OK).count()
                    : action.report().explicitClientMods().size() + action.report().otherExtraMods().size();
            graphics.drawString(font, fit(Component.translatable(key, count).getString(), right() - left() - 12),
                    left() + 5, y + 6, 0xFFFFFF);
            return;
        }
        if (row.unmet() != null) {
            Comparison.Result result = row.unmet();
            graphics.drawString(font, fit(result.required().displayName(), right() - left() - 12),
                    left() + 6, y + 3, 0xFFFFFF);
            graphics.drawString(font, fit(Component.translatable("jane.direct.required_id",
                    result.required().modId()).getString(), right() - left() - 12), left() + 6, y + 16, 0xCCCCCC);
            String status = !result.retainedCandidates().isEmpty() && result.status() != Comparison.Status.OK
                    ? Component.translatable("jane.environment.unsafe_duplicate").getString()
                    : Component.translatable("jane.status."
                    + result.status().name().toLowerCase(Locale.ROOT)).getString();
            graphics.drawString(font, fit(Component.translatable("jane.direct.required_state",
                    result.required().version(), status).getString(), right() - left() - 12),
                    left() + 6, y + 29, result.status() == Comparison.Status.FILE_ERROR ? 0xFF7777 : 0xFFCC77);
        } else {
            ClientCompatibilityReport.ExtraMod extra = row.extra();
            graphics.drawString(font, fit(extra.displayName(), right() - left() - 12),
                    left() + 6, y + 3, 0xFFFFFF);
            graphics.drawString(font, fit(Component.translatable("jane.direct.extra_id", extra.modId(),
                    extra.version()).getString(), right() - left() - 12), left() + 6, y + 16, 0xCCCCCC);
            String label = extra.sha512() == null && extra.kind() == ClientCompatibilityReport.Kind.EXPLICIT_CLIENT
                    ? "jane.direct.extra_unverified"
                    : extra.kind() == ClientCompatibilityReport.Kind.EXPLICIT_CLIENT
                    ? "jane.compat.explicit" : "jane.compat.other";
            graphics.drawString(font, fit(Component.translatable(label).getString(), right() - left() - 12),
                    left() + 6, y + 29, extra.sha512() == null
                            && extra.kind() == ClientCompatibilityReport.Kind.EXPLICIT_CLIENT ? 0xFF7777 : 0xFFCC77);
        }
    }

    private void drawScrollbar(GuiGraphics graphics, int x, int top, int view, int total, int offset) {
        if (view <= 0 || total <= view) return;
        int thumb = Math.max(12, view * view / total);
        int thumbY = top + offset * (view - thumb) / (total - view);
        graphics.fill(x, top, x + 4, top + view, 0xFF444444);
        graphics.fill(x, thumbY, x + 4, thumbY + thumb, 0xFFAAAAAA);
    }

    private String fit(String value, int maxWidth) {
        if (font.width(value) <= maxWidth) return value;
        return font.plainSubstrByWidth(value, Math.max(0, maxWidth - font.width("…"))) + "…";
    }

    private void confirm() {
        if (joining || !mayJoin()) {
            notice = Component.translatable("jane.environment.file_error_block");
            return;
        }
        if (!audited) {
            audited = true;
            if (!JaneClient.recordDirectJoinDecision(action.context(), action.report())) {
                warningShown = true;
                notice = Component.translatable("jane.direct.audit_continue");
                return;
            }
        }
        if (auditIncomplete() && !warningShown) {
            warningShown = true;
            notice = Component.translatable("jane.direct.audit_continue");
            return;
        }
        joining = true;
        if (!JaneClient.confirmDirectJoin(minecraft, action.context(), action.report(), action.target())) {
            joining = false;
            notice = Component.translatable("jane.direct.reconnect_failed");
        }
    }

    @Override public boolean mouseScrolled(double x, double y, double amount) {
        if (y >= statementTop() && y < statementTop() + statementViewport()) {
            statementScroll -= (int) Math.round(amount * 24);
            clamp(risks());
            return true;
        }
        if (y >= listTop() && y < listBottom()) {
            listScroll -= (int) Math.round(amount * 24);
            clamp(risks());
            return true;
        }
        return super.mouseScrolled(x, y, amount);
    }

    @Override public void onClose() { minecraft.setScreen(parent); }
}
