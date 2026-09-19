package dev.modsbyfox.jane.fabric.client;

import dev.modsbyfox.jane.core.Comparison;
import dev.modsbyfox.jane.core.JaneSyncSession;
import dev.modsbyfox.jane.core.PendingRecovery;
import dev.modsbyfox.jane.core.PendingSyncContext;
import dev.modsbyfox.jane.core.ResolutionPlan;
import dev.modsbyfox.jane.core.StagingWorkspace;
import dev.modsbyfox.jane.core.SyncNotice;
import dev.modsbyfox.jane.core.UpdatePlan;
import dev.modsbyfox.jane.fabric.JaneLog;
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
    private final AtomicBoolean sessionCancelled = new AtomicBoolean();
    private volatile AtomicBoolean activeTransferCancel;
    private volatile StagingWorkspace workspace;
    private boolean retryingLookup;
    private boolean launching;
    private UpdatePlan ready;
    private Component notice;
    private Button trustedButton;
    private Button serverRouteButton;
    private Button serverOnlyButton;
    private Button retryButton;
    private Button cancelTransferButton;
    private Button finishButton;

    SyncScreen(Screen parent, PendingSyncContext context) {
        super(Component.translatable("jane.sync.title"));
        this.parent = parent;
        this.session = new JaneSyncSession(context);
    }

    @Override protected void init() {
        clearWidgets();
        int buttonWidth = Math.min(220, width - 40);
        int x = (width - buttonWidth) / 2;
        trustedButton = addRenderableWidget(Button.builder(Component.translatable("jane.sync.resolve_files"), button -> {
            if (!session.resolutionStarted()) beginResolve();
            else start(ResolutionPlan.TransferGroup.TRUSTED, ResolutionPlan.TransferRoute.TRUSTED_SOURCE, false);
        }).bounds(x, height - 53, buttonWidth, 20).build());
        serverRouteButton = addRenderableWidget(Button.builder(Component.translatable("jane.sync.download_server_route"),
                button -> start(ResolutionPlan.TransferGroup.TRUSTED, ResolutionPlan.TransferRoute.CURRENT_SERVER, false))
                .bounds(x, height - 77, buttonWidth, 20).build());
        serverOnlyButton = addRenderableWidget(Button.builder(Component.translatable("jane.sync.review_server_only"),
                button -> minecraft.setScreen(new ServerDownloadConfirmScreen(this, session)))
                .bounds(x, height - 77, buttonWidth, 20).build());
        retryButton = addRenderableWidget(Button.builder(Component.translatable("jane.sync.retry_lookup"),
                button -> retryLookupFailures()).bounds(x, height - 101, buttonWidth, 20).build());
        cancelTransferButton = addRenderableWidget(Button.builder(Component.translatable("jane.sync.cancel_transfer"),
                button -> cancelTransfer()).bounds(x, height - 53, buttonWidth, 20).build());
        finishButton = addRenderableWidget(Button.builder(Component.translatable("jane.sync.finish"), button -> launch())
                .bounds(x, height - 53, buttonWidth, 20).build());
        int smallWidth = Math.min(130, (width - 50) / 2);
        addRenderableWidget(Button.builder(Component.translatable("jane.sync.details"), button ->
                minecraft.setScreen(new SyncDetailsScreen(this, session)))
                .bounds(width / 2 - smallWidth - 3, height - 28, smallWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("jane.sync.cancel"), button -> onClose())
                .bounds(width / 2 + 3, height - 28, smallWidth, 20).build());
    }

    private void beginResolve() {
        if (sessionCancelled.get() || !session.beginResolution(session.context().results().size())) return;
        CompletableFuture.runAsync(() -> {
            try { StagingService.resolve(session, sessionCancelled::get); }
            catch (InterruptedException exception) { throw new java.util.concurrent.CompletionException(exception); }
        }).whenComplete((unused, error) -> minecraft.execute(() -> {
            if (sessionCancelled.get()) return;
            if (error != null) {
                LOGGER.error("{}resolution failed", JaneLog.client(session.context().serverId()), error);
                session.finishTransfer(false, "resolve");
                notice = Component.translatable("jane.sync.failed");
            } else {
                JaneSyncSession.Snapshot snapshot = session.snapshot();
                notice = snapshot.resolution().count(ResolutionPlan.Availability.ALREADY_PRESENT)
                        == snapshot.resolution().items().size()
                        ? Component.translatable("jane.sync.already_present") : noticeFor(snapshot);
            }
        }));
    }

    private void retryLookupFailures() {
        if (retryingLookup || sessionCancelled.get() || session.snapshot().running()
                || session.snapshot().resolution() == null
                || session.snapshot().resolution().count(ResolutionPlan.Availability.LOOKUP_FAILED) == 0) return;
        retryingLookup = true;
        notice = Component.translatable("jane.sync.resolving");
        CompletableFuture.runAsync(() -> {
            try { StagingService.retryLookupFailures(session, sessionCancelled::get); }
            catch (InterruptedException exception) { throw new java.util.concurrent.CompletionException(exception); }
        }).whenComplete((unused, error) -> minecraft.execute(() -> {
            retryingLookup = false;
            if (sessionCancelled.get()) return;
            if (error != null) {
                LOGGER.error("{}lookup retry failed", JaneLog.client(session.context().serverId()), error);
                notice = Component.translatable("jane.sync.failed");
            } else notice = noticeFor(session.snapshot());
        }));
    }

    void startServerDownloads() {
        start(ResolutionPlan.TransferGroup.SERVER_ONLY, ResolutionPlan.TransferRoute.CURRENT_SERVER, true);
    }

    private void start(ResolutionPlan.TransferGroup group, ResolutionPlan.TransferRoute route, boolean confirmed) {
        if (sessionCancelled.get() || retryingLookup) return;
        JaneSyncSession.Snapshot before = session.snapshot();
        boolean started = confirmed ? session.startServerOnlyConfirmed() : session.startTransfer(group, route);
        if (!started) return;
        if (before.activeRoute() != null && before.activeRoute() != route)
            LOGGER.info("{}route switched old={} new={} remaining={}", JaneLog.client(session.context().serverId()),
                    before.activeRoute(), route, before.remainingCount(group));
        AtomicBoolean token = new AtomicBoolean(false);
        activeTransferCancel = token;
        long batchStarted = System.nanoTime();
        Path gameDir = FabricLoader.getInstance().getGameDir();
        CompletableFuture.supplyAsync(() -> {
            try {
                if (workspace == null) {
                    workspace = StagingWorkspace.create(gameDir);
                    LOGGER.info("{}workspace created", JaneLog.sync(workspace.syncId()));
                }
                if (sessionCancelled.get() || token.get()) throw new InterruptedException("Sync cancelled");
                return StagingService.stage(session, gameDir, workspace, group, route,
                        () -> sessionCancelled.get() || token.get());
            } catch (Exception exception) { throw new java.util.concurrent.CompletionException(exception); }
        }).whenComplete((outcome, error) -> minecraft.execute(() -> {
            boolean closed = sessionCancelled.get();
            // Once pending is prepared, a late click cannot undo a completed transfer batch.
            boolean transferStopped = closed || (token.get() && (outcome == null || outcome.plan() == null));
            if (closed) {
                session.finishTransfer(true, null);
                if (outcome != null && outcome.plan() != null) {
                    CompletableFuture.runAsync(() -> {
                        try { PendingRecovery.abandon(gameDir,
                                new PendingRecovery.Item(outcome.plan().syncId(), outcome.plan(), false, null)); }
                        catch (IOException exception) { LOGGER.error("Could not discard cancelled Jane sync", exception); }
                    });
                } else discardWorkspace();
            } else if (transferStopped) {
                JaneSyncSession.Snapshot interrupted = session.snapshot();
                long currentBytes = Math.max(0, interrupted.downloadedBytes() - interrupted.batchReadyBytesAtStart());
                session.finishTransfer(true, null);
                JaneSyncSession.Snapshot snapshot = session.snapshot();
                LOGGER.info("{}transfer cancelled route={} ready={} remaining={} currentBytes={} durationMs={}",
                        JaneLog.client(session.context().serverId()), route, snapshot.readyCount(),
                        snapshot.remainingCount(group), currentBytes,
                        (System.nanoTime() - batchStarted) / 1_000_000);
                notice = noticeFor(snapshot);
            } else if (error != null) {
                LOGGER.error("{}staging failed group={} route={}", JaneLog.client(session.context().serverId()),
                        group, route, error);
                session.finishTransfer(false, "staging");
                notice = Component.translatable("jane.sync.failed");
            } else {
                session.finishTransfer(false, null);
                ready = outcome.plan();
                notice = noticeFor(session.snapshot());
            }
            activeTransferCancel = null;
        }));
    }

    private void cancelTransfer() {
        AtomicBoolean token = activeTransferCancel;
        if (token != null) token.set(true);
    }

    private Component noticeFor(JaneSyncSession.Snapshot snapshot) {
        return switch (SyncNotice.select(snapshot, ready != null)) {
            case CONFIRM -> Component.translatable("jane.sync.confirm", session.context().results().size());
            case SOURCE_SELECTION -> Component.translatable("jane.sync.source_selection");
            case LOOKUP_FAILED -> Component.translatable("jane.sync.lookup_failed_count",
                    snapshot.resolution().count(ResolutionPlan.Availability.LOOKUP_FAILED));
            case TRUSTED_PARTIAL -> Component.translatable("jane.sync.ready_remaining",
                    snapshot.readyCount(ResolutionPlan.Availability.TRUSTED_AVAILABLE),
                    snapshot.resolution().count(ResolutionPlan.Availability.TRUSTED_AVAILABLE),
                    snapshot.remainingCount(ResolutionPlan.TransferGroup.TRUSTED));
            case TRUSTED_FAILED -> Component.translatable("jane.sync.failed_files",
                    snapshot.failedCount(ResolutionPlan.Availability.TRUSTED_AVAILABLE));
            case SERVER_ONLY_REMAINING -> Component.translatable("jane.sync.server_remaining",
                    snapshot.readyCount(ResolutionPlan.Availability.TRUSTED_AVAILABLE),
                    snapshot.remainingCount(ResolutionPlan.TransferGroup.SERVER_ONLY));
            case SERVER_ONLY_FAILED -> Component.translatable("jane.sync.server_failed",
                    snapshot.failedCount(ResolutionPlan.Availability.SERVER_ONLY));
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

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        JaneSyncSession.Snapshot snapshot = session.snapshot();
        ResolutionPlan plan = snapshot.resolution();
        boolean idle = !snapshot.running() && !retryingLookup && snapshot.error() == null && ready == null;
        boolean trustedRemaining = plan != null && snapshot.hasWaiting(ResolutionPlan.TransferGroup.TRUSTED);
        trustedButton.visible = ready == null && (!session.resolutionStarted() || trustedRemaining);
        trustedButton.active = trustedButton.visible && idle;
        if (session.resolutionStarted() && plan != null) trustedButton.setMessage(Component.translatable(
                snapshot.readyCount(ResolutionPlan.Availability.TRUSTED_AVAILABLE) > 0
                        ? "jane.sync.continue_trusted" : "jane.sync.download_trusted_route"));
        serverRouteButton.visible = idle && trustedRemaining && session.context().provider() != null;
        serverRouteButton.setMessage(Component.translatable(
                snapshot.readyCount(ResolutionPlan.Availability.TRUSTED_AVAILABLE) > 0
                        ? "jane.sync.server_remaining_route" : "jane.sync.download_server_route"));
        serverOnlyButton.visible = idle && plan != null && session.context().provider() != null
                && snapshot.groupReady(ResolutionPlan.TransferGroup.TRUSTED)
                && snapshot.hasWaiting(ResolutionPlan.TransferGroup.SERVER_ONLY);
        retryButton.visible = idle && plan != null && plan.count(ResolutionPlan.Availability.LOOKUP_FAILED) > 0;
        cancelTransferButton.visible = snapshot.running();
        cancelTransferButton.active = snapshot.running() && activeTransferCancel != null && !activeTransferCancel.get();
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
            graphics.drawCenteredString(font, Component.translatable("jane.sync.needed", needed), width / 2, 61, 0xFFCC77);
        } else if (plan == null) {
            graphics.drawCenteredString(font, Component.translatable("jane.sync.resolving_progress",
                    snapshot.resolutionProcessed(), snapshot.resolutionTotal(), snapshot.resolutionPercent()),
                    width / 2, 61, 0xFFCC77);
            drawBar(graphics, 75, snapshot.resolutionPercent());
        } else {
            int groupY = 49;
            if (plan.count(ResolutionPlan.Availability.TRUSTED_AVAILABLE) > 0) {
                drawGroup(graphics, "jane.sync.trusted_available", plan, ResolutionPlan.Availability.TRUSTED_AVAILABLE, groupY, 0xAAFFAA);
                groupY += 25;
            }
            if (plan.count(ResolutionPlan.Availability.SERVER_ONLY) > 0) {
                drawGroup(graphics, "jane.sync.server_only", plan, ResolutionPlan.Availability.SERVER_ONLY, groupY, 0xFFCC77);
                groupY += 25;
            }
            if (!snapshot.running()) {
                if (plan.count(ResolutionPlan.Availability.LOOKUP_FAILED) > 0)
                    graphics.drawCenteredString(font, Component.translatable("jane.sync.lookup_failed_count",
                            plan.count(ResolutionPlan.Availability.LOOKUP_FAILED)), width / 2, groupY, 0xFF7777);
                else if (plan.count(ResolutionPlan.Availability.UNRESOLVED) > 0)
                    graphics.drawCenteredString(font, Component.translatable("jane.sync.unresolved_count",
                            plan.count(ResolutionPlan.Availability.UNRESOLVED)), width / 2, groupY, 0xFF7777);
            } else {
                String progressKey = snapshot.activeGroup() == ResolutionPlan.TransferGroup.SERVER_ONLY
                        ? "jane.sync.server_preparing" : snapshot.activeRoute() == ResolutionPlan.TransferRoute.CURRENT_SERVER
                        ? "jane.sync.current_server_preparing" : "jane.sync.trusted_preparing";
                graphics.drawCenteredString(font, Component.translatable(progressKey, snapshot.processedCount(),
                        snapshot.batchTotal(), snapshot.progressPercent()), width / 2, 106, 0xFFFFFF);
                drawBar(graphics, 120, snapshot.progressPercent());
                graphics.drawCenteredString(font, Component.translatable(snapshot.activeRoute()
                        == ResolutionPlan.TransferRoute.TRUSTED_SOURCE ? "jane.sync.trusted_slow_hint" : "jane.sync.server_slow_hint"),
                        width / 2, 133, 0xFFCC77);
            }
        }
        if (notice != null && !snapshot.running()) {
            var lines = font.split(notice, Math.max(100, width - 24));
            int shown = Math.min(2, lines.size());
            for (int index = 0; index < shown; index++) graphics.drawCenteredString(font, lines.get(index),
                    width / 2, height - 116 - (shown - 1 - index) * 11, 0xFFCC77);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    static String mib(long bytes) { return String.format(java.util.Locale.ROOT, "%.1f", bytes / 1048576.0); }
    private void drawGroup(GuiGraphics graphics, String label, ResolutionPlan plan,
                           ResolutionPlan.Availability availability, int y, int color) {
        graphics.drawCenteredString(font, Component.translatable(label), width / 2, y, 0xFFFFFF);
        graphics.drawCenteredString(font, Component.translatable("jane.sync.size_count", plan.count(availability),
                mib(plan.size(availability))), width / 2, y + 12, color);
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
        workspace = null;
        CompletableFuture.runAsync(() -> {
            try { current.discard(FabricLoader.getInstance().getGameDir()); }
            catch (IOException exception) { LOGGER.warn("Could not discard Jane staging workspace", exception); }
        });
    }
    @Override public void onClose() {
        if (ready == null) {
            sessionCancelled.set(true);
            cancelTransfer();
            if (!session.snapshot().running()) discardWorkspace();
        }
        JaneClient.discardContext(session.context());
        minecraft.setScreen(parent);
    }
}
