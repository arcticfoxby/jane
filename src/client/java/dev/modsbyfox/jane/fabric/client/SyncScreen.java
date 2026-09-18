package dev.modsbyfox.jane.fabric.client;

import dev.modsbyfox.jane.core.Comparison;
import dev.modsbyfox.jane.core.JaneSyncSession;
import dev.modsbyfox.jane.core.PendingRecovery;
import dev.modsbyfox.jane.core.PendingSyncContext;
import dev.modsbyfox.jane.core.ResolutionPlan;
import dev.modsbyfox.jane.core.SyncNotice;
import dev.modsbyfox.jane.core.UpdatePlan;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class SyncScreen extends Screen {
    private static final Logger LOGGER = LoggerFactory.getLogger("jane");
    private final Screen parent;
    private final JaneSyncSession session;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private boolean resolveStarted;
    private boolean launching;
    private UpdatePlan ready;
    private Component notice;
    private Button downloadButton;
    private Button finishButton;

    SyncScreen(Screen parent, PendingSyncContext context) {
        super(Component.translatable("jane.sync.title"));
        this.parent = parent;
        this.session = new JaneSyncSession(context);
    }

    @Override
    protected void init() {
        clearWidgets();
        int buttonWidth = Math.min(220, width - 40);
        int x = (width - buttonWidth) / 2;
        int actionY = height - 54;
        downloadButton = addRenderableWidget(Button.builder(Component.translatable("jane.sync.download_trusted"), button -> start())
                .bounds(x, actionY, buttonWidth, 20).build());
        finishButton = addRenderableWidget(Button.builder(Component.translatable("jane.sync.finish"), button -> launch())
                .bounds(x, actionY, buttonWidth, 20).build());
        int smallWidth = Math.min(130, (width - 50) / 2);
        addRenderableWidget(Button.builder(Component.translatable("jane.sync.details"), button ->
                        minecraft.setScreen(new SyncDetailsScreen(this, session)))
                .bounds(width / 2 - smallWidth - 3, height - 28, smallWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("jane.sync.cancel"), button -> onClose())
                .bounds(width / 2 + 3, height - 28, smallWidth, 20).build());
        if (!resolveStarted) beginResolve();
    }

    private void beginResolve() {
        resolveStarted = true;
        CompletableFuture.runAsync(() -> {
            try {
                StagingService.resolve(session, cancelled::get);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new java.util.concurrent.CompletionException(exception);
            }
        }).whenComplete((unused, error) -> minecraft.execute(() -> {
            if (cancelled.get()) return;
            if (error != null) {
                LOGGER.error("Jane resolve failed", error);
                session.finish("resolve");
                notice = Component.translatable("jane.sync.failed");
            }
        }));
    }

    private void start() {
        if (!session.startDownloads()) return;
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
            if (error != null) {
                LOGGER.error("Jane staging failed", error);
                session.finish("staging");
                notice = Component.translatable("jane.sync.failed");
                return;
            }
            session.finish(null);
            ready = outcome.plan();
            JaneSyncSession.Snapshot snapshot = session.snapshot();
            notice = switch (SyncNotice.select(snapshot, ready != null)) {
                case CONFIRM -> Component.translatable("jane.sync.confirm");
                case FAILED_FILES -> Component.translatable("jane.sync.failed_files", snapshot.failedCount());
                case MANUAL_REMAINING -> Component.translatable("jane.sync.manual_remaining", snapshot.readyCount(),
                        snapshot.resolution().count(ResolutionPlan.Classification.UNRESOLVED));
                case FAILED_AND_MANUAL -> Component.translatable("jane.sync.failed_and_manual",
                        snapshot.resolution().count(ResolutionPlan.Classification.UNRESOLVED), snapshot.failedCount());
                case INCOMPLETE -> Component.translatable("jane.sync.incomplete");
            };
        }));
    }

    private void launch() {
        if (ready == null || launching || !session.snapshot().canInstall()) return;
        launching = true;
        notice = Component.translatable("jane.recovery.verifying");
        Path gameDir = FabricLoader.getInstance().getGameDir();
        CompletableFuture.runAsync(() -> {
            try { PendingRecovery.verifyForLaunch(gameDir, ready); }
            catch (IOException exception) { throw new java.util.concurrent.CompletionException(exception); }
        }).whenComplete((unused, error) -> minecraft.execute(() -> {
            launching = false;
            if (error != null) {
                LOGGER.error("Jane staged files changed before launch", error);
                notice = Component.translatable("jane.recovery.unsafe");
                return;
            }
            try { UpdaterLauncher.launch(minecraft, gameDir, ready); }
            catch (IOException exception) {
                LOGGER.error("Could not start Jane update helper", exception);
                notice = Component.translatable("jane.sync.launch_failed");
            }
        }));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        JaneSyncSession.Snapshot snapshot = session.snapshot();
        downloadButton.visible = ready == null;
        downloadButton.active = snapshot.resolution() != null && snapshot.error() == null && !snapshot.started()
                && !snapshot.resolution().downloadQueue().isEmpty();
        finishButton.visible = ready != null;
        finishButton.active = ready != null && !launching && snapshot.canInstall();
        graphics.drawCenteredString(font, title, width / 2, 16, 0xFFFFFF);
        graphics.drawCenteredString(font, Component.translatable("jane.sync.mismatch"), width / 2, 36, 0xFFAA55);
        long missing = session.context().results().stream().filter(r -> r.status() == Comparison.Status.MISSING).count();
        long updates = session.context().results().stream().filter(r -> r.status() == Comparison.Status.VERSION_MISMATCH).count();
        long broken = session.context().results().stream().filter(r -> r.status() == Comparison.Status.HASH_MISMATCH
                || r.status() == Comparison.Status.FILE_ERROR).count();
        graphics.drawCenteredString(font, Component.translatable("jane.sync.counts", missing, updates, broken), width / 2, 54, 0xFFFFFF);
        if (snapshot.resolution() == null) {
            graphics.drawCenteredString(font, Component.translatable(snapshot.error() == null ? "jane.sync.resolving" : "jane.sync.failed"),
                    width / 2, 92, 0xFFCC77);
        } else {
            ResolutionPlan plan = snapshot.resolution();
            graphics.drawCenteredString(font, Component.translatable("jane.sync.trusted"), width / 2, 76, 0xFFFFFF);
            graphics.drawCenteredString(font, Component.translatable("jane.sync.size_count", plan.count(ResolutionPlan.Classification.DOWNLOADABLE),
                    mib(plan.size(ResolutionPlan.Classification.DOWNLOADABLE))), width / 2, 90, 0xAAFFAA);
            graphics.drawCenteredString(font, Component.translatable("jane.sync.manual"), width / 2, 111, 0xFFFFFF);
            graphics.drawCenteredString(font, Component.translatable("jane.sync.size_count", plan.count(ResolutionPlan.Classification.UNRESOLVED),
                    mib(plan.size(ResolutionPlan.Classification.UNRESOLVED))), width / 2, 125, 0xFFAA55);
            if (snapshot.started()) {
                int completed = (int) snapshot.processedCount();
                int total = plan.downloadQueue().size();
                graphics.drawCenteredString(font, Component.translatable("jane.sync.preparing", completed, total,
                        snapshot.progressPercent()), width / 2, 144, 0xFFFFFF);
                int barWidth = Math.min(220, width - 40);
                int barX = (width - barWidth) / 2;
                graphics.fill(barX, 158, barX + barWidth, 166, 0xFF555555);
                graphics.fill(barX, 158, barX + barWidth * snapshot.progressPercent() / 100, 166, 0xFF55AA55);
            }
        }
        if (notice != null) graphics.drawCenteredString(font,
                Component.literal(font.plainSubstrByWidth(notice.getString(), Math.max(100, width - 24))),
                width / 2, height - 69, 0xFFCC77);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    static String mib(long bytes) {
        return String.format(java.util.Locale.ROOT, "%.1f", bytes / 1048576.0);
    }

    @Override
    public void onClose() {
        if (ready == null) cancelled.set(true);
        JaneClient.discardContext(session.context());
        minecraft.setScreen(parent);
    }
}
