package dev.modsbyfox.jane.fabric.client;

import dev.modsbyfox.jane.core.Comparison;
import dev.modsbyfox.jane.core.ClientInstallCategory;
import dev.modsbyfox.jane.core.DownloadCoordinator;
import dev.modsbyfox.jane.core.JaneSyncSession;
import dev.modsbyfox.jane.core.OneShotJoinOverride;
import dev.modsbyfox.jane.core.PendingRecovery;
import dev.modsbyfox.jane.core.PendingSyncContext;
import dev.modsbyfox.jane.core.ResolutionPlan;
import dev.modsbyfox.jane.core.StagingWorkspace;
import dev.modsbyfox.jane.core.SyncDecisionAudit;
import dev.modsbyfox.jane.core.SyncSelection;
import dev.modsbyfox.jane.core.SyncNotice;
import dev.modsbyfox.jane.core.UpdatePlan;
import dev.modsbyfox.jane.fabric.JaneLog;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final SyncSelection selection;
    private final ReconnectTarget reconnectTarget;
    private final AtomicBoolean sessionCancelled = new AtomicBoolean();
    private volatile AtomicBoolean activeTransferCancel;
    private volatile StagingWorkspace workspace;
    private boolean retryingLookup;
    private boolean resolved;
    private boolean launching;
    private boolean auditWarning;
    private boolean trustedSourceEnabled = SyncSelectionLayout.DEFAULT_TRUSTED_SOURCE;
    private boolean serverSourceEnabled = SyncSelectionLayout.DEFAULT_SERVER_SOURCE;
    private boolean downloadPaused;
    private DownloadCoordinator downloadCoordinator;
    private final Set<String> auditedOptionals = new HashSet<>();
    private UpdatePlan ready;
    private Component notice;
    private Button downloadButton;
    private Button retryButton;
    private Button cancelTransferButton;
    private Button finishButton;
    private Button skipButton;
    private Button detailsButton;
    private Button selectionButton;
    private Button cancelButton;

    SyncScreen(Screen parent, PendingSyncContext context, ReconnectTarget reconnectTarget) {
        this(parent, context, reconnectTarget, false);
    }

    SyncScreen(Screen parent, PendingSyncContext context, ReconnectTarget reconnectTarget, boolean auditSaveFailed) {
        super(Component.translatable("jane.sync.title"));
        this.parent = parent;
        this.session = new JaneSyncSession(context);
        this.selection = new SyncSelection(context);
        this.session.selectModIds(selection.selectedModIds());
        this.reconnectTarget = reconnectTarget;
        this.auditWarning = auditSaveFailed;
    }

    @Override protected void init() {
        clearWidgets();
        ClassicSyncLayout layout = ClassicSyncLayout.forSize(width, height);
        int x = layout.mainX();
        int buttonWidth = layout.mainWidth();
        downloadButton = addRenderableWidget(Button.builder(Component.translatable("jane.sync.download_needed"),
                button -> startDownloadWorkflow()).bounds(x, layout.mainY(), buttonWidth, 20).build());
        retryButton = addRenderableWidget(Button.builder(Component.translatable("jane.sync.retry_lookup"),
                button -> retryLookupFailures()).bounds(x, layout.mainY(), buttonWidth, 20).build());
        cancelTransferButton = addRenderableWidget(Button.builder(Component.translatable("jane.sync.cancel_transfer"),
                button -> cancelTransfer()).bounds(x, layout.mainY(), buttonWidth, 20).build());
        finishButton = addRenderableWidget(Button.builder(Component.translatable("jane.sync.finish"), button -> launch())
                .bounds(x, layout.mainY(), buttonWidth, 20).build());
        skipButton = addRenderableWidget(Button.builder(Component.translatable("jane.select.try_join"), button -> openRisk())
                .bounds(x, layout.mainY(), buttonWidth, 20).build());
        selectionButton = addRenderableWidget(Button.builder(Component.translatable("jane.sync.select_items"), button ->
                minecraft.setScreen(new SyncSelectionScreen(this, session, selection)))
                .bounds(x, layout.selectionY(), buttonWidth, 20).build());
        int footerWidth = layout.footerWidth();
        detailsButton = addRenderableWidget(Button.builder(Component.translatable("jane.sync.details"), button ->
                minecraft.setScreen(new SyncDetailsScreen(this, session)))
                .bounds(layout.compact() ? layout.compactFooterX(0) : width / 2 - footerWidth - 3,
                        layout.footerY(), footerWidth, 20).build());
        cancelButton = addRenderableWidget(Button.builder(Component.translatable("jane.sync.cancel"), button -> onClose())
                .bounds(layout.compact() ? layout.compactFooterX(2) : width / 2 + 3,
                        layout.footerY(), footerWidth, 20).build());
        if (layout.compact()) {
            detailsButton.setMessage(Component.translatable("jane.select.details_short"));
            selectionButton.setMessage(Component.translatable("jane.sync.select_short"));
            selectionButton.setX(layout.compactFooterX(1));
            selectionButton.setY(layout.footerY());
            selectionButton.setWidth(footerWidth);
        }
        // Minecraft calls init again when returning from Details or a confirmation screen.
        // beginResolution is a session-level one-shot guard, so no second lookup is launched.
        beginResolve();
    }

    private void beginResolve() {
        if (sessionCancelled.get() || !SyncSelectionLayout.beginResolutionOnce(session)) return;
        CompletableFuture.runAsync(() -> {
            try { StagingService.resolve(session, selection, sessionCancelled::get); }
            catch (InterruptedException exception) { throw new java.util.concurrent.CompletionException(exception); }
        }).whenComplete((unused, error) -> minecraft.execute(() -> {
            if (sessionCancelled.get()) return;
            if (error != null) {
                LOGGER.error("{}resolution failed", JaneLog.client(session.context().serverId()), error);
                session.finishTransfer(false, "resolve");
                notice = Component.translatable("jane.sync.failed");
            } else {
                resolved = true;
                auditDefaultOptionals();
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
        audit("USER_RETRY_LOOKUP", Map.of("selectedCount", Integer.toString(selection.selectedUnmatchedModIds().size())));
        CompletableFuture.runAsync(() -> {
            try { StagingService.retryLookupFailures(session, selection, sessionCancelled::get); }
            catch (InterruptedException exception) { throw new java.util.concurrent.CompletionException(exception); }
        }).whenComplete((unused, error) -> minecraft.execute(() -> {
            retryingLookup = false;
            if (sessionCancelled.get()) return;
            if (error != null) {
                LOGGER.error("{}lookup retry failed", JaneLog.client(session.context().serverId()), error);
                notice = Component.translatable("jane.sync.failed");
            } else {
                auditDefaultOptionals();
                notice = noticeFor(session.snapshot());
            }
        }));
    }

    void startServerDownloads() {
        DownloadCoordinator coordinator = downloadCoordinator;
        if (coordinator == null) return;
        coordinator.confirmServerOnly();
        audit("SERVER_ONLY_CONFIRMED", Map.of("selectedCount",
                Integer.toString(selection.selectedUnmatchedModIds().size())));
        advanceDownloadWorkflow();
    }

    void serverConfirmationDeclined() {
        if (downloadCoordinator == null) return;
        audit("SERVER_ONLY_DECLINED", Map.of("selectedCount",
                Integer.toString(selection.selectedUnmatchedModIds().size())));
        downloadCoordinator = null;
        downloadPaused = true;
        notice = Component.translatable("jane.sync.download_paused");
    }

    private void startDownloadWorkflow() {
        JaneSyncSession.Snapshot snapshot = session.snapshot();
        if (!resolved || sessionCancelled.get() || retryingLookup || launching || ready != null
                || snapshot.running() || selectedPendingCount(snapshot) == 0
                || (!trustedSourceEnabled && !usableServerSource())) return;
        downloadPaused = false;
        downloadCoordinator = new DownloadCoordinator(trustedSourceEnabled, serverSourceEnabled,
                session.context().provider() != null);
        audit("DOWNLOAD_CHOICE", Map.of("reason", "AUTOMATIC",
                "selectedCount", Integer.toString(selection.selectedUnmatchedModIds().size()),
                "downloadCount", Integer.toString(selectedPendingCount(snapshot))));
        advanceDownloadWorkflow();
    }

    private void advanceDownloadWorkflow() {
        DownloadCoordinator coordinator = downloadCoordinator;
        if (coordinator == null || sessionCancelled.get()) return;
        JaneSyncSession.Snapshot snapshot = session.snapshot();
        switch (coordinator.next(snapshot)) {
            case TRUSTED -> start(ResolutionPlan.TransferGroup.TRUSTED,
                    ResolutionPlan.TransferRoute.TRUSTED_SOURCE, false);
            case TRUSTED_SERVER_FALLBACK -> {
                audit(trustedSourceEnabled ? "DOWNLOAD_ROUTE_FALLBACK" : "DOWNLOAD_ROUTE_SELECTED",
                        Map.of("reason", trustedSourceEnabled ? "TRUSTED_TO_SERVER" : "CURRENT_SERVER"));
                start(ResolutionPlan.TransferGroup.TRUSTED,
                        ResolutionPlan.TransferRoute.CURRENT_SERVER, false);
            }
            case SERVER_ONLY_CONFIRM -> minecraft.setScreen(new ServerDownloadConfirmScreen(this, session));
            case SERVER_ONLY -> start(ResolutionPlan.TransferGroup.SERVER_ONLY,
                    ResolutionPlan.TransferRoute.CURRENT_SERVER, true);
            case COMPLETE -> {
                downloadCoordinator = null;
                if (ready != null) notice = Component.translatable("jane.sync.selected_completed");
                else notice = noticeFor(snapshot);
            }
            case LOOKUP_FAILED -> {
                downloadCoordinator = null;
                notice = Component.translatable("jane.sync.lookup_failed_count",
                        selectedAvailabilityCount(snapshot, ResolutionPlan.Availability.LOOKUP_FAILED));
            }
            case NO_SOURCE -> {
                downloadCoordinator = null;
                notice = Component.translatable("jane.sync.no_source");
            }
            case UNAVAILABLE, INCOMPLETE -> {
                downloadCoordinator = null;
                notice = noticeFor(snapshot);
            }
        }
    }

    private boolean usableServerSource() {
        return serverSourceEnabled && session.context().provider() != null;
    }

    private int selectedPendingCount(JaneSyncSession.Snapshot snapshot) {
        return snapshot.queue(ResolutionPlan.TransferGroup.TRUSTED).size()
                + snapshot.queue(ResolutionPlan.TransferGroup.SERVER_ONLY).size();
    }

    private void start(ResolutionPlan.TransferGroup group, ResolutionPlan.TransferRoute route, boolean confirmed) {
        if (sessionCancelled.get() || retryingLookup) return;
        JaneSyncSession.Snapshot before = session.snapshot();
        boolean started = confirmed ? session.startServerOnlyConfirmed() : session.startTransfer(group, route);
        if (!started) {
            downloadCoordinator = null;
            notice = noticeFor(before);
            return;
        }
        selection.freezeChoices();
        audit("DOWNLOAD_ROUTE_STARTED", Map.of("reason", route.name(),
                "downloadCount", Integer.toString(before.queue(group).size())));
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
                downloadCoordinator = null;
                downloadPaused = true;
                notice = Component.translatable("jane.sync.download_paused");
            } else if (error != null) {
                LOGGER.error("{}staging failed group={} route={}", JaneLog.client(session.context().serverId()),
                        group, route, error);
                session.finishTransfer(false, "staging");
                downloadCoordinator = null;
                notice = Component.translatable("jane.sync.failed");
            } else {
                session.finishTransfer(false, null);
                ready = outcome.plan();
                notice = ready != null ? Component.translatable("jane.sync.selected_completed")
                        : noticeFor(session.snapshot());
            }
            if (activeTransferCancel == token) activeTransferCancel = null;
            if (!closed && !transferStopped && error == null && ready == null) advanceDownloadWorkflow();
        }));
    }

    private void cancelTransfer() {
        AtomicBoolean token = activeTransferCancel;
        if (token != null && !token.getAndSet(true))
            audit("USER_PAUSED_DOWNLOAD", Map.of("selectedCount",
                    Integer.toString(selection.selectedUnmatchedModIds().size())));
    }

    private Component noticeFor(JaneSyncSession.Snapshot snapshot) {
        return switch (SyncNotice.select(snapshot, ready != null)) {
            case CONFIRM -> Component.translatable("jane.select.selected_ready",
                    selection.selectedUnmatchedModIds().size(), skippedItems().size());
            case LOCAL_ERROR -> Component.translatable("jane.sync.local_fix_required");
            case RUNTIME_LOCAL_ERROR -> Component.translatable("jane.sync.local_runtime_error_count",
                    snapshot.localErrorCount());
            case SOURCE_SELECTION -> Component.translatable("jane.sync.source_selection");
            case LOOKUP_FAILED -> Component.translatable("jane.sync.lookup_failed_count",
                    selectedAvailabilityCount(snapshot, ResolutionPlan.Availability.LOOKUP_FAILED));
            case TRUSTED_PARTIAL -> Component.translatable("jane.sync.ready_remaining",
                    snapshot.readyCount(ResolutionPlan.Availability.TRUSTED_AVAILABLE),
                    selectedAvailabilityCount(snapshot, ResolutionPlan.Availability.TRUSTED_AVAILABLE),
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

    private long selectedAvailabilityCount(JaneSyncSession.Snapshot snapshot, ResolutionPlan.Availability availability) {
        return snapshot.items().stream().filter(item -> selection.isSelected(item.item().comparison().required().modId())
                && item.item().availability() == availability).count();
    }

    private void launch() {
        if (ready == null || launching || !session.snapshot().canInstall()) return;
        audit("USER_INSTALL_CONFIRMED", Map.of("selectedCount",
                Integer.toString(selection.selectedUnmatchedModIds().size())));
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
        updateButtons(snapshot);
        ClassicSyncLayout layout = ClassicSyncLayout.forSize(width, height);
        graphics.drawCenteredString(font, title, width / 2, layout.tiny() ? 5 : 8, 0xFFFFFF);
        graphics.drawCenteredString(font, fit(Component.translatable("jane.sync.mismatch").getString(), width - 16),
                width / 2, layout.tiny() ? 17 : 22, 0xFFAA55);
        String counts = Component.translatable("jane.sync.counts", count(Comparison.Status.MISSING),
                count(Comparison.Status.VERSION_MISMATCH),
                count(Comparison.Status.HASH_MISMATCH) + count(Comparison.Status.FILE_ERROR)).getString();
        graphics.drawCenteredString(font, fit(counts, width - 16), width / 2,
                layout.tiny() ? 29 : 35, 0xFFFFFF);
        if (layout.tiny()) renderTinySummary(graphics, snapshot);
        else renderClassicSummary(graphics, snapshot, layout);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderClassicSummary(GuiGraphics graphics, JaneSyncSession.Snapshot snapshot,
                                      ClassicSyncLayout layout) {
        ResolutionPlan plan = snapshot.resolution();
        if (plan == null) {
            Component text = snapshot.error() == null
                    ? Component.translatable("jane.sync.resolving_progress",
                    snapshot.resolutionProcessed(), snapshot.resolutionTotal(), snapshot.resolutionPercent())
                    : Component.translatable("jane.sync.failed");
            graphics.drawCenteredString(font, fit(text.getString(), width - 16), width / 2, 61, 0xFFCC77);
            if (snapshot.error() == null) drawBar(graphics, 75, snapshot.resolutionPercent());
            return;
        }
        int groupY = 49;
        if (selectedAvailabilityCount(snapshot, ResolutionPlan.Availability.TRUSTED_AVAILABLE) > 0) {
            drawGroup(graphics, "jane.sync.trusted_available", snapshot,
                    ResolutionPlan.Availability.TRUSTED_AVAILABLE, groupY, 0xAAFFAA);
            groupY += 25;
        }
        if (selectedAvailabilityCount(snapshot, ResolutionPlan.Availability.SERVER_ONLY) > 0) {
            drawGroup(graphics, "jane.sync.server_only", snapshot,
                    ResolutionPlan.Availability.SERVER_ONLY, groupY, 0xFFCC77);
            groupY += 25;
        }
        if (snapshot.running()) {
            drawActiveProgress(graphics, snapshot, 106, 120, 133);
            return;
        }
        if (auditWarning) {
            graphics.drawCenteredString(font, fit(Component.translatable("jane.select.audit_incomplete")
                    .getString(), width - 16), width / 2, groupY, 0xFF7777);
            groupY += 12;
        } else if (snapshot.localErrorCount() > 0) {
            graphics.drawCenteredString(font, Component.translatable("jane.sync.local_runtime_error_count",
                    snapshot.localErrorCount()), width / 2, groupY, 0xFF7777);
            groupY += 12;
        } else if (plan.count(ResolutionPlan.Availability.LOCAL_ERROR) > 0) {
            graphics.drawCenteredString(font, Component.translatable("jane.sync.local_error_count",
                    plan.count(ResolutionPlan.Availability.LOCAL_ERROR)), width / 2, groupY, 0xFF7777);
            groupY += 12;
        } else if (plan.count(ResolutionPlan.Availability.LOOKUP_FAILED) > 0) {
            graphics.drawCenteredString(font, Component.translatable("jane.sync.lookup_failed_count",
                    plan.count(ResolutionPlan.Availability.LOOKUP_FAILED)), width / 2, groupY, 0xFF7777);
            groupY += 12;
        }
        Component shown = notice;
        int noticeBottom = groupY + 3;
        if (shown != null) {
            var lines = font.split(shown, Math.max(80, width - 24));
            ClassicSyncLayout.Notice placement = layout.notice(groupY + 3, lines.size(), retryButton.visible);
            for (int index = 0; index < placement.lines(); index++)
                graphics.drawCenteredString(font, lines.get(index), width / 2,
                        placement.firstY() + index * 11,
                        auditWarning ? 0xFF7777 : 0xFFCC77);
            if (placement.lines() > 0)
                noticeBottom = placement.firstY() + (placement.lines() - 1) * 11;
        }
        if (ready != null) {
            Component hint = Component.translatable("jane.sync.confirm_move_hint");
            int hintY = Math.max(noticeBottom + 15, height - 98);
            var lines = font.split(hint, Math.max(80, width - 16));
            for (int index = 0; index < lines.size() && hintY + index * 11 + 9 < layout.actionTop(false); index++)
                graphics.drawCenteredString(font, lines.get(index), width / 2,
                        hintY + index * 11, 0xBBBBBB);
        }
    }

    private void renderTinySummary(GuiGraphics graphics, JaneSyncSession.Snapshot snapshot) {
        ResolutionPlan plan = snapshot.resolution();
        if (plan == null) {
            Component text = snapshot.error() == null
                    ? Component.translatable("jane.sync.resolving_progress",
                    snapshot.resolutionProcessed(), snapshot.resolutionTotal(), snapshot.resolutionPercent())
                    : Component.translatable("jane.sync.failed");
            graphics.drawCenteredString(font, fit(text.getString(), width - 12), width / 2, 44, 0xFFCC77);
            if (snapshot.error() == null) drawBar(graphics, 60, snapshot.resolutionPercent());
            return;
        }
        if (snapshot.running()) {
            drawActiveProgress(graphics, snapshot, 43, 66, 55);
            return;
        }
        if (ready != null) {
            graphics.drawCenteredString(font, fit(Component.translatable("jane.sync.selected_completed").getString(),
                    width - 12), width / 2, 43, 0xFFCC77);
            var lines = font.split(Component.translatable("jane.sync.confirm_move_hint_compact"),
                    Math.max(80, width - 12));
            for (int index = 0; index < lines.size() && index < 2; index++)
                graphics.drawCenteredString(font, lines.get(index), width / 2, 55 + index * 11, 0xBBBBBB);
            return;
        }
        String sources = Component.translatable("jane.sync.source_counts_compact",
                selectedAvailabilityCount(snapshot, ResolutionPlan.Availability.TRUSTED_AVAILABLE),
                selectedAvailabilityCount(snapshot, ResolutionPlan.Availability.SERVER_ONLY),
                plan.count(ResolutionPlan.Availability.LOOKUP_FAILED)).getString();
        graphics.drawCenteredString(font, fit(sources, width - 12), width / 2, 43, 0xAAFFAA);
        Component shown = auditWarning ? Component.translatable("jane.select.audit_incomplete")
                : notice != null ? notice
                : Component.translatable("jane.select.summary",
                selection.selectedUnmatchedModIds().size(), skippedItems().size());
        graphics.drawCenteredString(font, fit(shown.getString(), width - 12), width / 2, 55,
                auditWarning ? 0xFF7777 : 0xFFCC77);
        if (!trustedSourceEnabled && !usableServerSource()
                || plan.count(ResolutionPlan.Availability.LOOKUP_FAILED) > 0) {
            Component issue = !trustedSourceEnabled && !usableServerSource()
                    ? Component.translatable("jane.sync.no_source")
                    : Component.translatable("jane.sync.lookup_failed_count",
                    plan.count(ResolutionPlan.Availability.LOOKUP_FAILED));
            graphics.drawCenteredString(font, fit(issue.getString(), width - 12),
                    width / 2, 67, 0xFF7777);
        }
    }

    private void drawActiveProgress(GuiGraphics graphics, JaneSyncSession.Snapshot snapshot,
                                    int itemY, int barY, int routeY) {
        JaneSyncSession.ItemState current = snapshot.items().stream().filter(item ->
                selection.isSelected(item.item().comparison().required().modId())
                        && (item.state() == JaneSyncSession.RuntimeState.DOWNLOADING
                        || item.state() == JaneSyncSession.RuntimeState.VERIFYING
                        || item.state() == JaneSyncSession.RuntimeState.RESOLVING)).findFirst().orElse(null);
        int total = selection.selectedUnmatchedModIds().size();
        String name = current == null ? Component.translatable("jane.sync.preparing_item").getString()
                : current.item().comparison().required().displayName();
        String item = Component.translatable("jane.sync.current_item", name,
                Math.min(total, (int) snapshot.readyCount() + 1), total).getString();
        graphics.drawCenteredString(font, fit(item, width - 16), width / 2, itemY, 0xFFFFFF);
        drawBar(graphics, barY, snapshot.overallProgressPercent());
        String route = Component.translatable(snapshot.activeRoute() == ResolutionPlan.TransferRoute.CURRENT_SERVER
                ? "jane.details.current_server" : "jane.details.trusted_source").getString();
        graphics.drawCenteredString(font, fit(Component.translatable("jane.sync.route_progress", route,
                snapshot.overallProgressPercent()).getString(), width - 16), width / 2, routeY, 0xFFCC77);
    }

    private void drawGroup(GuiGraphics graphics, String label, JaneSyncSession.Snapshot snapshot,
                           ResolutionPlan.Availability availability, int y, int color) {
        graphics.drawCenteredString(font, Component.translatable(label), width / 2, y, 0xFFFFFF);
        graphics.drawCenteredString(font, Component.translatable("jane.sync.size_count",
                selectedAvailabilityCount(snapshot, availability),
                mib(selectedAvailabilitySize(snapshot, availability))), width / 2, y + 12, color);
    }

    private long selectedAvailabilitySize(JaneSyncSession.Snapshot snapshot, ResolutionPlan.Availability availability) {
        return snapshot.items().stream().filter(item ->
                selection.isSelected(item.item().comparison().required().modId())
                        && item.item().availability() == availability)
                .mapToLong(item -> item.item().comparison().required().fileSize()).sum();
    }

    private void drawBar(GuiGraphics graphics, int y, int percent) {
        int barWidth = Math.max(1, Math.min(220, width - 40));
        int barX = (width - barWidth) / 2;
        int clamped = Math.max(0, Math.min(100, percent));
        graphics.fill(barX, y, barX + barWidth, y + 8, 0xFF555555);
        graphics.fill(barX, y, barX + barWidth * clamped / 100, y + 8, 0xFF55AA55);
    }

    private String fit(String value, int maxWidth) {
        if (maxWidth <= 0) return "";
        if (font.width(value) <= maxWidth) return value;
        return font.plainSubstrByWidth(value, Math.max(0, maxWidth - font.width("…"))) + "…";
    }

    static String mib(long bytes) { return String.format(java.util.Locale.ROOT, "%.1f", bytes / 1048576.0); }

    private long count(Comparison.Status status) {
        return session.context().results().stream().filter(result -> result.status() == status).count();
    }

    private List<Comparison.Result> skippedItems() {
        return session.context().results().stream().filter(result -> result.status() != Comparison.Status.OK
                && !selection.isSelected(result.required().modId())).toList();
    }

    private boolean canAttemptOverride() {
        JaneSyncSession.Snapshot snapshot = session.snapshot();
        return (!session.resolutionStarted() || resolved) && !snapshot.running() && !snapshot.started()
                && !retryingLookup && ready == null && reconnectTarget != null && snapshot.error() == null
                && snapshot.localErrorCount() == 0 && selection.selectedUnmatchedModIds().isEmpty()
                && !skippedItems().isEmpty()
                && OneShotJoinOverride.overrideable(session.context().manifest(), session.context().results());
    }

    private void updateButtons(JaneSyncSession.Snapshot snapshot) {
        downloadButton.visible = retryButton.visible = false;
        cancelTransferButton.visible = finishButton.visible = skipButton.visible = false;
        selectionButton.visible = true;
        selectionButton.active = !launching && !sessionCancelled.get();
        if (launching) return;
        Button primary = downloadButton;
        Button secondary = null;
        if (snapshot.running()) {
            primary = cancelTransferButton;
            cancelTransferButton.active = activeTransferCancel != null && !activeTransferCancel.get();
        } else if (ready != null) {
            primary = finishButton;
            finishButton.active = snapshot.canInstall();
        } else {
            downloadButton.setMessage(Component.translatable(resolved
                    ? "jane.sync.download_needed" : "jane.sync.resolving_button"));
            downloadButton.active = resolved && !retryingLookup && snapshot.error() == null
                    && snapshot.localErrorCount() == 0 && selectedPendingCount(snapshot) > 0
                    && (trustedSourceEnabled && snapshot.hasWaiting(ResolutionPlan.TransferGroup.TRUSTED)
                    || usableServerSource() && (snapshot.hasWaiting(ResolutionPlan.TransferGroup.TRUSTED)
                    || snapshot.hasWaiting(ResolutionPlan.TransferGroup.SERVER_ONLY)));
            if (canAttemptOverride()) primary = skipButton;
            boolean lookupFailed = snapshot.resolution() != null
                    && snapshot.resolution().count(ResolutionPlan.Availability.LOOKUP_FAILED) > 0;
            if (resolved && lookupFailed) secondary = retryButton;
            retryButton.active = !retryingLookup;
        }
        placeMain(primary, secondary);
    }

    private void placeMain(Button primary, Button secondary) {
        ClassicSyncLayout layout = ClassicSyncLayout.forSize(width, height);
        primary.visible = true;
        primary.setY(layout.mainY());
        primary.setX(layout.mainX());
        primary.setWidth(layout.mainWidth());
        if (layout.compact()) {
            if (secondary != null) {
                int half = (layout.mainWidth() - 4) / 2;
                primary.setWidth(half);
                secondary.setX(layout.mainX() + half + 4);
                secondary.setY(layout.mainY());
                secondary.setWidth(half);
                secondary.visible = true;
            }
            selectionButton.setX(layout.compactFooterX(1));
            selectionButton.setY(layout.footerY());
            selectionButton.setWidth(layout.footerWidth());
        } else {
            if (secondary != null) {
                secondary.setX(layout.mainX());
                secondary.setY(layout.selectionY());
                secondary.setWidth(layout.mainWidth());
                secondary.visible = true;
            }
            selectionButton.setX(layout.mainX());
            selectionButton.setY(secondary == null ? layout.selectionY() : layout.retryY());
            selectionButton.setWidth(layout.mainWidth());
        }
    }

    boolean trustedSourceEnabled() { return trustedSourceEnabled; }
    boolean serverSourceEnabled() { return serverSourceEnabled; }
    boolean serverSourceAvailable() { return session.context().provider() != null; }
    boolean sourceSettingsEditable() {
        return !sessionCancelled.get() && !session.snapshot().running()
                && downloadCoordinator == null && !launching && ready == null;
    }
    boolean selectionEditable() {
        JaneSyncSession.Snapshot snapshot = session.snapshot();
        return !sessionCancelled.get() && (!session.resolutionStarted() || resolved)
                && !snapshot.running() && !snapshot.started() && !retryingLookup
                && !launching && ready == null;
    }

    void toggleSource(boolean server) {
        if (!sourceSettingsEditable()
                || server && session.context().provider() == null) return;
        if (server) serverSourceEnabled = !serverSourceEnabled;
        else trustedSourceEnabled = !trustedSourceEnabled;
        audit("DOWNLOAD_SOURCE_SETTING", Map.of("reason", server ? "CURRENT_SERVER" : "TRUSTED_SOURCE",
                "status", Boolean.toString(server ? serverSourceEnabled : trustedSourceEnabled)));
        notice = !trustedSourceEnabled && !usableServerSource()
                ? Component.translatable("jane.sync.no_source") : null;
    }

    void toggleSelection(Comparison.Result result) {
        if (result.status() == Comparison.Status.OK) return;
        if (!selectionEditable()) return;
        String modId = result.required().modId();
        boolean wasSelected = selection.isSelected(modId);
        selection.setSelected(modId, !wasSelected);
        if (!session.selectModIds(selection.selectedModIds())) return;
        ClientInstallCategory category = selection.category(modId);
        String event = result.status() == Comparison.Status.OK ? "USER_TOGGLE_MATCHED"
                : category == ClientInstallCategory.CLIENT_OPTIONAL
                ? (wasSelected ? "USER_DESELECT_OPTIONAL" : "USER_SELECT_OPTIONAL")
                : (wasSelected ? "USER_DESELECT_REQUIRED" : "USER_SELECT_REQUIRED");
        audit(event, Map.of("modId", modId, "requiredVersion", result.required().version(),
                "status", result.status().name(), "selectedCount", Integer.toString(selection.selectedUnmatchedModIds().size())));
        notice = Component.translatable("jane.select.summary", selection.selectedUnmatchedModIds().size(),
                skippedItems().size());
    }

    void bulkRequiredSelection(boolean select) {
        if (!selectionEditable()) return;
        for (Comparison.Result result : session.context().results()) {
            if (result.status() != Comparison.Status.OK
                    && selection.category(result.required().modId()) == ClientInstallCategory.SERVER_REQUIRED
                    && selection.isSelected(result.required().modId()) != select) toggleSelection(result);
        }
    }

    private void openRisk() {
        if (!canAttemptOverride()) return;
        minecraft.setScreen(new SyncRiskConfirmScreen(this, session.context(), selection));
    }

    boolean canConfirmOverride() { return canAttemptOverride(); }
    boolean auditIncomplete() { return auditWarning; }

    /** Returns false when the separate audit is incomplete; callers may warn before continuing. */
    boolean recordOverrideDecision() {
        if (!canAttemptOverride()) return false;
        boolean saved = true;
        Set<String> defaultOptional = selection.defaultOptionalExclusionModIds();
        for (Comparison.Result result : skippedItems()) {
            String modId = result.required().modId();
            String event = selection.category(modId) == ClientInstallCategory.SERVER_REQUIRED
                    ? "SKIPPED_REQUIRED" : defaultOptional.contains(modId)
                    ? "DEFAULT_OPTIONAL_EXCLUSION" : "SKIPPED_OPTIONAL";
            String reason = selection.category(modId) == ClientInstallCategory.SERVER_REQUIRED
                    ? "USER_DESELECTED" : defaultOptional.contains(modId) ? "DEFAULT_OPTIONAL" : "USER_DESELECTED";
            saved &= audit(event, Map.of("modId", modId, "requiredVersion", result.required().version(),
                    "status", result.status().name(), "reason", reason));
        }
        saved &= audit("USER_OVERRIDE_CONFIRMED", Map.of("skippedRequired",
                Integer.toString(selection.deselectedRequiredModIds().size()),
                "selectionTimestamp", selection.selectionTimestamp().toString()));
        saved &= audit("ATTEMPT_WITH_OVERRIDE", Map.of("requiredBaseline", "NOT_SATISFIED",
                "joinAction", "ATTEMPT_WITH_OVERRIDE"));
        return saved;
    }

    boolean requestOverrideJoin() {
        if (!canAttemptOverride()) return false;
        boolean started = JaneClient.confirmRequiredOverride(minecraft, session.context(), reconnectTarget, selection);
        if (!started) notice = Component.translatable("jane.select.reconnect_failed");
        return started;
    }

    private void auditDefaultOptionals() {
        for (String modId : selection.defaultOptionalExclusionModIds()) {
            if (!auditedOptionals.add(modId)) continue;
            audit("DEFAULT_OPTIONAL_EXCLUSION", Map.of("modId", modId,
                    "requiredVersion", session.context().manifest().entries().stream()
                            .filter(entry -> entry.modId().equals(modId)).findFirst().orElseThrow().version()));
        }
    }

    private boolean audit(String event, Map<String, String> fields) {
        Map<String, String> values = new HashMap<>(fields);
        values.put("serverId", session.context().serverId());
        values.put("manifestDigest", selection.manifestDigest());
        LOGGER.info("{}syncDecision={} modId={} selected={} skippedRequired={}",
                JaneLog.client(session.context().serverId()), event, fields.getOrDefault("modId", "none"),
                selection.selectedUnmatchedModIds().size(), selection.deselectedRequiredModIds().size());
        try {
            SyncDecisionAudit.record(FabricLoader.getInstance().getGameDir(), event, values);
            return true;
        } catch (IOException exception) {
            auditWarning = true;
            LOGGER.error("{}sync decision audit write failed event={}",
                    JaneLog.client(session.context().serverId()), event, exception);
            return false;
        }
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
        audit("USER_CANCEL_SYNC", Map.of("selectedCount", Integer.toString(selection.selectedUnmatchedModIds().size())));
        if (ready == null) {
            sessionCancelled.set(true);
            cancelTransfer();
            if (!session.snapshot().running()) discardWorkspace();
        }
        JaneClient.discardContext(session.context());
        minecraft.setScreen(parent);
    }
}
