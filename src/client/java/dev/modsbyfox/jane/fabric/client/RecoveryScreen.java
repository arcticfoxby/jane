package dev.modsbyfox.jane.fabric.client;

import dev.modsbyfox.jane.core.UpdatePlan;
import dev.modsbyfox.jane.core.PendingRecovery;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class RecoveryScreen extends Screen {
    private static final Logger LOGGER = LoggerFactory.getLogger("jane");
    private final Screen parent;
    private final PendingRecovery.Item pending;
    private boolean details;
    private boolean busy;
    private UpdatePlan ready;
    private Component notice;

    RecoveryScreen(Screen parent, PendingRecovery.Item pending) {
        super(Component.translatable("jane.recovery.title"));
        this.parent = parent;
        this.pending = pending;
    }

    @Override
    protected void init() {
        clearWidgets();
        int y = height - 30;
        Button retry = Button.builder(Component.translatable(ready == null ? "jane.recovery.retry" : "jane.sync.finish"), button -> retry())
                .bounds(width / 2 - 155, y, 100, 20).build();
        retry.active = pending.plan() != null && !busy;
        addRenderableWidget(retry);
        addRenderableWidget(Button.builder(Component.translatable("jane.sync.details"), button -> details = !details)
                .bounds(width / 2 - 50, y, 100, 20).build());
        Button abandon = Button.builder(Component.translatable("jane.recovery.abandon"), button -> abandon())
                .bounds(width / 2 + 55, y, 100, 20).build();
        abandon.active = !busy;
        addRenderableWidget(abandon);
    }

    private void retry() {
        if (ready != null) {
            busy = true;
            rebuildWidgets();
            Path gameDir = FabricLoader.getInstance().getGameDir();
            CompletableFuture.runAsync(() -> {
                try { PendingRecovery.verifyForLaunch(gameDir, ready); }
                catch (Exception exception) { throw new java.util.concurrent.CompletionException(exception); }
            }).whenComplete((unused, error) -> minecraft.execute(() -> {
                busy = false;
                if (error != null) {
                    LOGGER.error("Jane recovery files changed before launch", error);
                    notice = Component.translatable("jane.recovery.unsafe");
                    rebuildWidgets();
                    return;
                }
                try { UpdaterLauncher.launch(minecraft, gameDir, ready); }
                catch (Exception exception) {
                    LOGGER.error("Could not launch Jane recovery helper", exception);
                    notice = Component.translatable("jane.sync.launch_failed");
                    rebuildWidgets();
                }
            }));
            return;
        }
        busy = true;
        notice = Component.translatable("jane.recovery.verifying");
        rebuildWidgets();
        Path gameDir = FabricLoader.getInstance().getGameDir();
        CompletableFuture.supplyAsync(() -> {
            try {
                return PendingRecovery.prepareRetry(gameDir, pending);
            } catch (Exception exception) {
                throw new java.util.concurrent.CompletionException(exception);
            }
        }).whenComplete((plan, error) -> minecraft.execute(() -> {
            busy = false;
            if (error != null) {
                LOGGER.error("Jane retry cannot be prepared", error);
                notice = Component.translatable("jane.recovery.unsafe");
            } else {
                ready = plan;
                notice = Component.translatable("jane.sync.confirm");
            }
            rebuildWidgets();
        }));
    }

    private void abandon() {
        busy = true;
        rebuildWidgets();
        Path gameDir = FabricLoader.getInstance().getGameDir();
        CompletableFuture.runAsync(() -> {
            try {
                PendingRecovery.abandon(gameDir, pending);
            } catch (Exception exception) {
                throw new java.util.concurrent.CompletionException(exception);
            }
        }).whenComplete((unused, error) -> minecraft.execute(() -> {
            busy = false;
            if (error != null) {
                LOGGER.error("Could not discard Jane pending data", error);
                notice = Component.translatable("jane.recovery.abandon_failed");
                rebuildWidgets();
            } else {
                JaneClient.recheckPending();
                minecraft.setScreen(parent);
            }
        }));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, 20, 0xFFFFFF);
        graphics.drawCenteredString(font, Component.translatable("jane.recovery.incomplete"), width / 2, 54, 0xFFAA55);
        if (details) {
            graphics.drawCenteredString(font, Component.literal("Sync ID: " + pending.syncId()), width / 2, 78, 0xCCCCCC);
            graphics.drawCenteredString(font, Component.literal("State: " + (pending.failed() ? "FAILED" : "INTERRUPTED")), width / 2, 94, 0xCCCCCC);
            if (pending.plan() != null) {
                graphics.drawCenteredString(font, Component.literal("Operations: " + pending.plan().operations().size()), width / 2, 110, 0xCCCCCC);
            }
            if (pending.issue() != null) graphics.drawCenteredString(font, Component.translatable("jane.recovery.corrupt"), width / 2, 126, 0xFF7777);
        }
        if (ready != null) {
            graphics.drawCenteredString(font, Component.translatable("jane.sync.confirm_line2"), width / 2, height - 82, 0xCCCCCC);
            graphics.drawCenteredString(font, Component.translatable("jane.sync.confirm_line3"), width / 2, height - 66, 0xCCCCCC);
        }
        if (notice != null) graphics.drawCenteredString(font, notice, width / 2, height - 48, 0xFFCC77);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
