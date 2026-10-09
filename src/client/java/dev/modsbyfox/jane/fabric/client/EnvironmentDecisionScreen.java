package dev.modsbyfox.jane.fabric.client;

import dev.modsbyfox.jane.core.Comparison;
import dev.modsbyfox.jane.core.OneShotJoinOverride;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Local assessment and the player's choice of what to do next; no source lookup occurs here. */
final class EnvironmentDecisionScreen extends Screen {
    private final Screen parent;
    private final PendingClientAction.EnvironmentDecision action;
    private boolean auditWarning;
    private Component notice;
    private Button directButton;

    EnvironmentDecisionScreen(Screen parent, PendingClientAction.EnvironmentDecision action) {
        super(Component.translatable("jane.environment.title"));
        this.parent = parent;
        this.action = action;
        this.auditWarning = action.auditSaveFailed();
    }

    @Override protected void init() {
        clearWidgets();
        boolean compact = height < 190;
        boolean tiny = height < 145;
        int buttonWidth = Math.max(1, Math.min(240, width - 24));
        int x = (width - buttonWidth) / 2;
        if (compact) {
            int half = Math.max(1, (width - 30) / 2);
            int left = (width - (half * 2 + 6)) / 2;
            int firstY = tiny ? height - 46 : height - 52;
            int secondY = firstY + 24;
            addRenderableWidget(Button.builder(Component.translatable("jane.environment.sync_short"),
                    button -> sync()).bounds(left, firstY, half, 20).build());
            directButton = addRenderableWidget(Button.builder(Component.translatable("jane.environment.direct_short"),
                    button -> direct()).bounds(left + half + 6, firstY, half, 20).build());
            addRenderableWidget(Button.builder(Component.translatable("jane.environment.details_short"),
                    button -> details()).bounds(left, secondY, half, 20).build());
            addRenderableWidget(Button.builder(Component.translatable("gui.back"),
                    button -> onClose()).bounds(left + half + 6, secondY, half, 20).build());
        } else {
            int firstY = height - 102;
            addRenderableWidget(Button.builder(Component.translatable("jane.environment.sync"),
                    button -> sync()).bounds(x, firstY, buttonWidth, 20).build());
            directButton = addRenderableWidget(Button.builder(Component.translatable("jane.environment.direct"),
                    button -> direct()).bounds(x, firstY + 24, buttonWidth, 20).build());
            addRenderableWidget(Button.builder(Component.translatable("jane.environment.details"),
                    button -> details()).bounds(x, firstY + 48, buttonWidth, 20).build());
            addRenderableWidget(Button.builder(Component.translatable("gui.back"),
                    button -> onClose()).bounds(x, firstY + 72, buttonWidth, 20).build());
        }
    }

    private boolean requiredPassed() { return Comparison.passed(action.context().results()); }

    private boolean mayJoin() {
        return action.target() != null && (requiredPassed()
                || OneShotJoinOverride.overrideable(action.context().manifest(), action.context().results()));
    }

    boolean auditIncomplete() { return auditWarning; }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        directButton.active = mayJoin();
        graphics.drawCenteredString(font, title, width / 2, 8, 0xFFFFFF);
        long matched = action.context().results().stream().filter(result -> result.status() == Comparison.Status.OK).count();
        long errors = action.context().results().stream().filter(result ->
                result.status() == Comparison.Status.FILE_ERROR
                        || result.status() != Comparison.Status.OK && !result.retainedCandidates().isEmpty()).count();
        int total = action.context().manifest().entries().size();
        int extras = action.report().explicitClientMods().size() + action.report().otherExtraMods().size();
        if (height < 100) {
            drawCentered(graphics, Component.translatable("jane.environment.compact_counts2", total - matched, extras),
                    18, 0xFFCC77);
        } else if (height < 145) {
            drawCentered(graphics, Component.translatable("jane.environment.compact_counts1", total, matched),
                    19, 0xFFFFFF);
            drawCentered(graphics, Component.translatable("jane.environment.compact_counts2", total - matched, extras),
                    31, 0xFFCC77);
            if (errors > 0) drawCentered(graphics,
                    Component.translatable("jane.environment.file_error", errors), 43, 0xFF7777);
            else if (notice != null || auditWarning) drawCentered(graphics,
                    notice != null ? notice : Component.translatable("jane.select.audit_incomplete"), 43, 0xFF7777);
        } else {
            drawCentered(graphics, Component.translatable("jane.environment.required", total), 28, 0xFFFFFF);
            drawCentered(graphics, Component.translatable("jane.environment.matched", matched), 40, 0xAAFFAA);
            drawCentered(graphics, Component.translatable("jane.environment.unmet", total - matched), 52, 0xFFCC77);
            drawCentered(graphics, Component.translatable("jane.environment.extras", extras), 64, 0xFFCC77);
            if (errors > 0) {
                drawCentered(graphics, Component.translatable("jane.environment.file_error", errors),
                        height < 190 ? 78 : 83, 0xFF7777);
            } else if (notice != null || auditWarning) {
                drawCentered(graphics, notice != null ? notice : Component.translatable("jane.select.audit_incomplete"),
                        height < 190 ? 78 : 83, 0xFF7777);
            }
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void drawCentered(GuiGraphics graphics, Component text, int y, int color) {
        String value = text.getString();
        int available = Math.max(0, width - 20);
        if (font.width(value) > available)
            value = font.plainSubstrByWidth(value, Math.max(0, available - font.width("…"))) + "…";
        graphics.drawCenteredString(font, value, width / 2, y, color);
    }

    private void sync() {
        auditWarning |= !JaneClient.recordEnvironmentAction("USER_SELECT_SYNC", action.context(), action.report());
        if (requiredPassed()) {
            minecraft.setScreen(new CompatibilityReviewScreen(this,
                    new PendingClientAction.CompatibilityReview(action.context(), action.report(), action.target())));
        } else {
            minecraft.setScreen(new SyncScreen(this, action.context(), action.target(), auditWarning));
        }
    }

    private void direct() {
        if (!mayJoin()) {
            notice = Component.translatable("jane.environment.file_error_block");
            return;
        }
        auditWarning |= !JaneClient.recordEnvironmentAction("USER_SELECT_DIRECT_JOIN",
                action.context(), action.report());
        minecraft.setScreen(new DirectJoinConfirmScreen(this, action));
    }

    private void details() {
        minecraft.setScreen(new EnvironmentDetailsScreen(this, action));
    }

    @Override public void onClose() { minecraft.setScreen(parent); }
}
