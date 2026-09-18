package dev.modsbyfox.jane.fabric.client;

import dev.modsbyfox.jane.core.Comparison;
import dev.modsbyfox.jane.core.ManifestEntry;
import dev.modsbyfox.jane.core.PendingRecovery;
import dev.modsbyfox.jane.core.PendingSyncContext;
import dev.modsbyfox.jane.core.UpdatePlan;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class SyncScreen extends Screen {
    private static final Logger LOGGER = LoggerFactory.getLogger("jane");
    private final Screen parent;
    private final PendingSyncContext session;
    private boolean details;
    private boolean working;
    private int scroll;
    private UpdatePlan ready;
    private List<ManifestEntry> unavailable = List.of();
    private Component notice;
    private final AtomicBoolean cancelled = new AtomicBoolean();

    SyncScreen(Screen parent, PendingSyncContext session) {
        super(Component.translatable("jane.sync.title"));
        this.parent = parent;
        this.session = session;
    }

    @Override
    protected void init() {
        clearWidgets();
        int y = height - 30;
        if (ready != null) {
            addRenderableWidget(Button.builder(Component.translatable("jane.sync.finish"), button -> launch())
                    .bounds(width / 2 - 155, y, 200, 20).build());
            addRenderableWidget(Button.builder(Component.translatable("jane.sync.later"), button -> minecraft.setScreen(parent))
                    .bounds(width / 2 + 50, y, 105, 20).build());
        } else {
            Button sync = Button.builder(Component.translatable("jane.sync.start"), button -> start())
                    .bounds(width / 2 - 155, y, 100, 20).build();
            sync.active = !working && unavailable.isEmpty();
            addRenderableWidget(sync);
            addRenderableWidget(Button.builder(Component.translatable("jane.sync.details"), button -> details = !details)
                    .bounds(width / 2 - 50, y, 100, 20).build());
            addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose())
                    .bounds(width / 2 + 55, y, 100, 20).build());
        }
    }

    private void start() {
        working = true;
        notice = Component.translatable("jane.sync.downloading");
        rebuildWidgets();
        Path gameDir = FabricLoader.getInstance().getGameDir();
        CompletableFuture.supplyAsync(() -> {
            try {
                return StagingService.stage(session, gameDir, cancelled::get);
            } catch (Exception exception) {
                throw new java.util.concurrent.CompletionException(exception);
            }
        }).whenComplete((outcome, error) -> minecraft.execute(() -> {
            if (cancelled.get()) {
                if (outcome != null && outcome.plan() != null) {
                    CompletableFuture.runAsync(() -> {
                        try { PendingRecovery.abandon(gameDir, new PendingRecovery.Item(outcome.plan().syncId(), outcome.plan(), false, null)); }
                        catch (IOException exception) { LOGGER.error("Could not discard cancelled Jane sync", exception); }
                    });
                }
                return;
            }
            working = false;
            if (error != null) {
                LOGGER.error("Jane staging failed", error);
                notice = Component.translatable("jane.sync.failed");
            } else if (!outcome.unavailable().isEmpty()) {
                unavailable = outcome.unavailable();
                details = true;
                notice = Component.translatable("jane.sync.unavailable");
            } else {
                ready = outcome.plan();
                notice = Component.translatable("jane.sync.confirm");
            }
            rebuildWidgets();
        }));
    }

    private void launch() {
        if (ready == null || working) return;
        working = true;
        notice = Component.translatable("jane.recovery.verifying");
        Path gameDir = FabricLoader.getInstance().getGameDir();
        CompletableFuture.runAsync(() -> {
            try { PendingRecovery.verifyForLaunch(gameDir, ready); }
            catch (IOException exception) { throw new java.util.concurrent.CompletionException(exception); }
        }).whenComplete((unused, error) -> minecraft.execute(() -> {
            working = false;
            if (error != null) {
                LOGGER.error("Jane staged files changed before launch", error);
                notice = Component.translatable("jane.recovery.unsafe");
                return;
            }
            try {
                UpdaterLauncher.launch(minecraft, gameDir, ready);
            } catch (IOException exception) {
                LOGGER.error("Could not start Jane update helper", exception);
                notice = Component.translatable("jane.sync.launch_failed");
            }
        }));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, 20, 0xFFFFFF);
        if (ready != null) {
            graphics.drawCenteredString(font, Component.translatable("jane.sync.confirm_line1"), width / 2, 60, 0xFFFFFF);
            graphics.drawCenteredString(font, Component.translatable("jane.sync.confirm_line2"), width / 2, 76, 0xCCCCCC);
            graphics.drawCenteredString(font, Component.translatable("jane.sync.confirm_line3"), width / 2, 92, 0xCCCCCC);
        } else if (details) {
            int row = 0;
            List<Comparison.Result> results = session.results();
            int visible = Math.max(1, (height - 100) / 22);
            for (int i = scroll; i < Math.min(results.size(), scroll + visible); i++) {
                Comparison.Result result = results.get(i);
                int y = 48 + row++ * 22;
                graphics.drawString(font, Component.literal(result.required().displayName() + " (" + result.required().modId() + ")"), 20, y, 0xFFFFFF);
                String local = result.local() == null ? "-" : result.local().version();
                String shortHash = unavailable.contains(result.required()) ? "  SHA-512 " + result.required().sha512().substring(0, 12) : "";
                Component line = Component.literal(local + " → " + result.required().version() + "  ")
                        .append(Component.translatable("jane.status." + result.status().name().toLowerCase(java.util.Locale.ROOT)))
                        .append(Component.literal("  " + (result.required().fileSize() / 1024) + " KiB" + shortHash));
                if (unavailable.contains(result.required())) line = line.copy().append(Component.translatable("jane.sync.unavailable_item"));
                graphics.drawString(font, line, 20, y + 10, 0xCCCCCC);
            }
            if (!unavailable.isEmpty()) {
                graphics.drawCenteredString(font, Component.translatable("jane.sync.manual_count", unavailable.size()), width / 2,
                        height - 48, 0xFFAA55);
            }
        } else {
            long missing = session.results().stream().filter(r -> r.status() == Comparison.Status.MISSING).count();
            long updates = session.results().stream().filter(r -> r.status() == Comparison.Status.VERSION_MISMATCH).count();
            long broken = session.results().stream().filter(r -> r.status() == Comparison.Status.HASH_MISMATCH
                    || r.status() == Comparison.Status.FILE_ERROR).count();
            long files = missing + updates + broken;
            long size = session.results().stream().filter(r -> r.status() != Comparison.Status.OK)
                    .mapToLong(r -> r.required().fileSize()).sum();
            graphics.drawCenteredString(font, Component.translatable("jane.sync.mismatch"), width / 2, 56, 0xFFAA55);
            graphics.drawCenteredString(font, Component.translatable("jane.sync.counts", missing, updates, broken), width / 2, 78, 0xFFFFFF);
            graphics.drawCenteredString(font, Component.translatable("jane.sync.download_size", files, (size + 1024 * 1024 - 1) / (1024 * 1024)), width / 2, 94, 0xFFFFFF);
        }
        if (notice != null) graphics.drawCenteredString(font, notice, width / 2, height - 50, 0xFFCC77);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        int visible = Math.max(1, (height - 100) / 22);
        scroll = Math.max(0, Math.min(Math.max(0, session.results().size() - visible), scroll - (int) Math.signum(amount)));
        return true;
    }

    @Override
    public void onClose() {
        if (ready == null) cancelled.set(true);
        JaneClient.discardContext(session);
        minecraft.setScreen(parent);
    }
}
