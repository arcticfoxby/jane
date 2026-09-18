package dev.modsbyfox.jane.fabric.client;

import dev.modsbyfox.jane.core.Comparison;
import dev.modsbyfox.jane.core.JaneSyncSession;
import dev.modsbyfox.jane.core.PendingRecovery;
import dev.modsbyfox.jane.core.PendingSyncContext;
import dev.modsbyfox.jane.core.ResolutionPlan;
import dev.modsbyfox.jane.core.SyncNotice;
import dev.modsbyfox.jane.core.UpdatePlan;
import dev.modsbyfox.jane.core.StagingWorkspace;
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
    private boolean launching;
    private UpdatePlan ready;
    private volatile StagingWorkspace workspace;
    private CompletableFuture<?> activeTask;
    private boolean stageCallbackHandled;
    private Component notice;
    private Button downloadButton;
    private Button serverButton;
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
        int actionY = height - 53;
        downloadButton = addRenderableWidget(Button.builder(Component.translatable("jane.sync.download_prepare"), button -> beginResolve())
                .bounds(x, actionY, buttonWidth, 20).build());
        serverButton = addRenderableWidget(Button.builder(Component.translatable("jane.sync.download_server"), button ->
                        minecraft.setScreen(new ServerDownloadConfirmScreen(this, session)))
                .bounds(x, height - 77, buttonWidth, 20).build());
        finishButton = addRenderableWidget(Button.builder(Component.translatable("jane.sync.finish"), button -> launch())
                .bounds(x, actionY, buttonWidth, 20).build());
        int smallWidth = Math.min(130, (width - 50) / 2);
        addRenderableWidget(Button.builder(Component.translatable("jane.sync.details"), button ->
                        minecraft.setScreen(new SyncDetailsScreen(this, session)))
                .bounds(width / 2 - smallWidth - 3, height - 28, smallWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("jane.sync.cancel"), button -> onClose())
                .bounds(width / 2 + 3, height - 28, smallWidth, 20).build());
    }

    private void beginResolve() {
        if (cancelled.get() || !session.beginResolution(session.context().results().size())) return;
        activeTask = CompletableFuture.runAsync(() -> {
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
            } else if (session.snapshot().resolution().count(ResolutionPlan.Classification.MODRINTH_DOWNLOADABLE) > 0) {
                start(ResolutionPlan.Classification.MODRINTH_DOWNLOADABLE);
            } else {
                JaneSyncSession.Snapshot snapshot = session.snapshot();
                notice = snapshot.resolution().count(ResolutionPlan.Classification.ALREADY_PRESENT)
                        == snapshot.resolution().items().size()
                        ? Component.translatable("jane.sync.already_present") : noticeFor(snapshot);
            }
        }));
    }

    void startServerDownloads() {
        start(ResolutionPlan.Classification.SERVER_DOWNLOADABLE);
    }

    private void start(ResolutionPlan.Classification provider) {
        if (!session.startDownloads(provider)) return;
        stageCallbackHandled = false;
        Path gameDir = FabricLoader.getInstance().getGameDir();
        activeTask = CompletableFuture.supplyAsync(() -> {
            try {
                if (workspace == null) workspace = StagingWorkspace.create(gameDir);
                if (cancelled.get()) throw new InterruptedException("Sync cancelled");
                return StagingService.stage(session, gameDir, workspace, provider, cancelled::get);
            } catch (Exception exception) {
                throw new java.util.concurrent.CompletionException(exception);
            }
        }).whenComplete((outcome, error) -> minecraft.execute(() -> {
            stageCallbackHandled = true;
            if (cancelled.get()) {
                if (outcome != null && outcome.plan() != null) {
                    CompletableFuture.runAsync(() -> {
                        try { PendingRecovery.abandon(gameDir, new PendingRecovery.Item(outcome.plan().syncId(), outcome.plan(), false, null)); }
                        catch (IOException exception) { LOGGER.error("Could not discard cancelled Jane sync", exception); }
                    });
                } else discardWorkspace();
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
            notice = noticeFor(snapshot);
        }));
    }

    private Component noticeFor(JaneSyncSession.Snapshot snapshot) {
        return switch (SyncNotice.select(snapshot, ready != null)) {
            case CONFIRM -> Component.translatable("jane.sync.confirm", session.context().results().size());
            case FAILED_FILES -> Component.translatable("jane.sync.failed_files", snapshot.failedCount());
            case MANUAL_REMAINING -> Component.translatable("jane.sync.manual_remaining", snapshot.readyCount(),
                    snapshot.resolution().count(ResolutionPlan.Classification.UNRESOLVED));
            case FAILED_AND_MANUAL -> Component.translatable("jane.sync.failed_and_manual",
                    snapshot.resolution().count(ResolutionPlan.Classification.UNRESOLVED), snapshot.failedCount());
            case SERVER_REMAINING -> Component.translatable("jane.sync.server_remaining",
                    snapshot.readyCount(ResolutionPlan.Classification.MODRINTH_DOWNLOADABLE),
                    snapshot.resolution().count(ResolutionPlan.Classification.SERVER_DOWNLOADABLE));
            case SERVER_FAILED -> Component.translatable("jane.sync.server_failed", snapshot.failedCount());
            case INCOMPLETE -> Component.translatable("jane.sync.incomplete");
        };
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
        downloadButton.active = downloadButton.visible && !session.resolutionStarted() && snapshot.error() == null;
        serverButton.visible = ready == null && snapshot.resolution() != null
                && snapshot.resolution().count(ResolutionPlan.Classification.SERVER_DOWNLOADABLE) > 0;
        serverButton.active = serverButton.visible && snapshot.error() == null && !snapshot.running()
                && snapshot.providerReady(ResolutionPlan.Classification.MODRINTH_DOWNLOADABLE)
                && snapshot.failedCount(ResolutionPlan.Classification.MODRINTH_DOWNLOADABLE) == 0
                && session.context().provider() != null
                && snapshot.hasWaiting(ResolutionPlan.Classification.SERVER_DOWNLOADABLE);
        finishButton.visible = ready != null;
        finishButton.active = ready != null && !launching && snapshot.canInstall();
        graphics.drawCenteredString(font, title, width / 2, 8, 0xFFFFFF);
        graphics.drawCenteredString(font, Component.translatable("jane.sync.mismatch"), width / 2, 22, 0xFFAA55);
        long missing = session.context().results().stream().filter(r -> r.status() == Comparison.Status.MISSING).count();
        long updates = session.context().results().stream().filter(r -> r.status() == Comparison.Status.VERSION_MISMATCH).count();
        long broken = session.context().results().stream().filter(r -> r.status() == Comparison.Status.HASH_MISMATCH
                || r.status() == Comparison.Status.FILE_ERROR).count();
        graphics.drawCenteredString(font, Component.translatable("jane.sync.counts", missing, updates, broken), width / 2, 35, 0xFFFFFF);
        if (!session.resolutionStarted()) {
            long needed = session.context().results().stream().filter(r -> r.status() != Comparison.Status.OK).count();
            graphics.drawCenteredString(font, Component.translatable("jane.sync.needed", needed),
                    width / 2, 61, 0xFFCC77);
        } else if (snapshot.resolution() == null) {
            graphics.drawCenteredString(font, snapshot.error() == null
                    ? Component.translatable("jane.sync.resolving_progress", snapshot.resolutionProcessed(),
                    snapshot.resolutionTotal(), snapshot.resolutionPercent())
                    : Component.translatable("jane.sync.failed"), width / 2, 61, 0xFFCC77);
            drawBar(graphics, 75, snapshot.resolutionPercent());
        } else {
            ResolutionPlan plan = snapshot.resolution();
            int groupY = 49;
            if (plan.count(ResolutionPlan.Classification.MODRINTH_DOWNLOADABLE) > 0) {
                drawGroup(graphics, "jane.sync.public_downloads", plan,
                        ResolutionPlan.Classification.MODRINTH_DOWNLOADABLE, groupY, 0xAAFFAA);
                groupY += 25;
            }
            if (plan.count(ResolutionPlan.Classification.SERVER_DOWNLOADABLE) > 0) {
                drawGroup(graphics, "jane.sync.server_downloads", plan,
                        ResolutionPlan.Classification.SERVER_DOWNLOADABLE, groupY, 0xFFCC77);
                groupY += 24;
            }
            if (plan.count(ResolutionPlan.Classification.UNRESOLVED) > 0) {
                if (groupY <= 74) drawGroup(graphics, "jane.sync.unresolved", plan,
                        ResolutionPlan.Classification.UNRESOLVED, groupY, 0xFF7777);
                else graphics.drawCenteredString(font, Component.translatable("jane.sync.unresolved_count",
                        plan.count(ResolutionPlan.Classification.UNRESOLVED)), width / 2, 98, 0xFF7777);
            }
            if (snapshot.started()) {
                int completed = (int) snapshot.processedCount();
                int total = plan.queue(snapshot.activeProvider()).size();
                graphics.drawCenteredString(font, Component.translatable(snapshot.activeProvider()
                        == ResolutionPlan.Classification.SERVER_DOWNLOADABLE ? "jane.sync.server_preparing" : "jane.sync.preparing",
                        completed, total, snapshot.progressPercent()), width / 2, 111, 0xFFFFFF);
                drawBar(graphics, 124, snapshot.progressPercent());
            }
        }
        if (notice != null) {
            var lines = font.split(notice, Math.max(100, width - 24));
            int shown = Math.min(2, lines.size());
            for (int i = 0; i < shown; i++) graphics.drawCenteredString(font, lines.get(i),
                    width / 2, height - 91 - (shown - 1 - i) * 11, 0xFFCC77);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    static String mib(long bytes) {
        return String.format(java.util.Locale.ROOT, "%.1f", bytes / 1048576.0);
    }

    private void drawGroup(GuiGraphics graphics, String label, ResolutionPlan plan,
                           ResolutionPlan.Classification classification, int y, int countColor) {
        graphics.drawCenteredString(font, Component.translatable(label), width / 2, y, 0xFFFFFF);
        graphics.drawCenteredString(font, Component.translatable("jane.sync.size_count", plan.count(classification),
                mib(plan.size(classification))), width / 2, y + 12, countColor);
    }

    private void drawBar(GuiGraphics graphics, int y, int percent) {
        int barWidth = Math.min(220, width - 40);
        int barX = (width - barWidth) / 2;
        graphics.fill(barX, y, barX + barWidth, y + 8, 0xFF555555);
        graphics.fill(barX, y, barX + barWidth * percent / 100, y + 8, 0xFF55AA55);
    }

    private void discardWorkspace() {
        StagingWorkspace current = workspace;
        if (current == null) return;
        CompletableFuture.runAsync(() -> {
            try { current.discard(FabricLoader.getInstance().getGameDir()); }
            catch (IOException exception) { LOGGER.warn("Could not discard Jane staging workspace", exception); }
        });
    }

    @Override
    public void onClose() {
        if (ready == null) cancelled.set(true);
        if (ready == null) {
            CompletableFuture<?> current = activeTask;
            if (current == null || stageCallbackHandled) discardWorkspace();
        }
        JaneClient.discardContext(session.context());
        minecraft.setScreen(parent);
    }
}
