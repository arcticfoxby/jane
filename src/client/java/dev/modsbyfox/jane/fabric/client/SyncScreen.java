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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
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
    private boolean requiredOpen = true;
    private boolean optionalOpen = true;
    private boolean matchedOpen = SyncSelectionLayout.DEFAULT_MATCHED_OPEN;
    private boolean sourceOpen = SyncSelectionLayout.DEFAULT_SOURCES_OPEN;
    private boolean trustedSourceEnabled = SyncSelectionLayout.DEFAULT_TRUSTED_SOURCE;
    private boolean serverSourceEnabled = SyncSelectionLayout.DEFAULT_SERVER_SOURCE;
    private boolean downloadPaused;
    private DownloadCoordinator downloadCoordinator;
    private int scroll;
    private int declarationScroll;
    private boolean draggingBar;
    private boolean draggingDeclarationBar;
    private final Set<String> auditedOptionals = new HashSet<>();
    private UpdatePlan ready;
    private Component notice;
    private Button downloadButton;
    private Button retryButton;
    private Button cancelTransferButton;
    private Button finishButton;
    private Button skipButton;
    private Button detailsButton;
    private Button cancelButton;

    private enum RowKind { REQUIRED_HEADER, MATCHED_HEADER, OPTIONAL_HEADER, BULK_REQUIRED,
        SOURCE_HEADER, SOURCE_TRUSTED, SOURCE_SERVER, NOTE, MOD }
    private record Row(RowKind kind, int height, Comparison.Result comparison,
                       FormattedCharSequence text) { }

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
        SyncSelectionLayout layout = layout(false);
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
        int smallWidth = (buttonWidth - 6) / 2;
        detailsButton = addRenderableWidget(Button.builder(Component.translatable("jane.sync.details"), button ->
                minecraft.setScreen(new SyncDetailsScreen(this, session)))
                .bounds(layout.compact() ? width - 29 : x, layout.footerY(),
                        layout.compact() ? 22 : smallWidth, layout.compact() ? 18 : 20).build());
        cancelButton = addRenderableWidget(Button.builder(Component.translatable("jane.sync.cancel"), button -> onClose())
                .bounds(layout.compact() ? 7 : x + smallWidth + 6, layout.footerY(),
                        layout.compact() ? 22 : smallWidth, layout.compact() ? 18 : 20).build());
        if (layout.compact()) {
            detailsButton.setMessage(Component.translatable("jane.select.details_short"));
            cancelButton.setMessage(Component.translatable("jane.select.back_short"));
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
                if (ready != null) scroll = 0;
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
        boolean progressVisible = snapshot.running() || snapshot.resolution() == null;
        SyncSelectionLayout layout = layout(progressVisible);
        graphics.drawCenteredString(font, title, width / 2, 5, 0xFFFFFF);
        if (!layout.compact())
            graphics.drawCenteredString(font, fit(Component.translatable("jane.select.server",
                    session.context().serverId().substring(0, 12)).getString(), width - 20), width / 2, 18, 0xBBBBBB);
        String counts = Component.translatable("jane.sync.counts",
                count(Comparison.Status.MISSING), count(Comparison.Status.VERSION_MISMATCH),
                count(Comparison.Status.HASH_MISMATCH) + count(Comparison.Status.FILE_ERROR)).getString();
        graphics.drawCenteredString(font, fit(counts, layout.compact() ? width - 66 : width - 16),
                width / 2, layout.compact() ? 17 : 30, 0xFFFFFF);
        Component status = auditWarning && !snapshot.running()
                ? Component.translatable("jane.select.audit_incomplete")
                : layout.compact() && !snapshot.running() && notice != null ? notice : statusLine(snapshot);
        graphics.drawCenteredString(font, fit(status.getString(), width - 16), width / 2,
                layout.compact() ? 29 : 42, 0xFFCC77);
        drawDeclaration(graphics, layout);
        List<Row> rows = rows();
        clampScroll(rows);
        graphics.enableScissor(layout.listLeft(), layout.listTop(), layout.listRight() + 8, layout.listBottom());
        int y = layout.listTop() - scroll;
        for (Row row : rows) {
            if (y + row.height() > layout.listTop() && y < layout.listBottom())
                drawRow(graphics, snapshot, row, y, mouseX, mouseY, layout);
            y += row.height();
        }
        graphics.disableScissor();
        int total = contentHeight(rows);
        int view = layout.listBottom() - layout.listTop();
        if (total > view) {
            int thumb = Math.max(12, view * view / total);
            int thumbY = layout.listTop() + scroll * (view - thumb) / (total - view);
            graphics.fill(layout.listRight() + 3, layout.listTop(), layout.listRight() + 7, layout.listBottom(), 0xFF444444);
            graphics.fill(layout.listRight() + 3, thumbY, layout.listRight() + 7, thumbY + thumb, 0xFFAAAAAA);
        }
        if (progressVisible) drawProgress(graphics, snapshot, layout);
        else if (!layout.compact()) {
            Component footer = auditWarning ? Component.translatable("jane.select.audit_incomplete")
                    : ready != null ? Component.translatable("jane.sync.confirm_move_hint") : notice;
            if (footer != null) graphics.drawCenteredString(font, fit(footer.getString(), width - 16),
                    width / 2, layout.progressTop(), auditWarning ? 0xFF7777 : 0xAAAAAA);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private SyncSelectionLayout layout(boolean showProgress) {
        SyncSelectionLayout base = SyncSelectionLayout.forSize(width, height);
        int contentWidth = Math.max(40, base.listRight() - base.listLeft() - 12);
        int lines = font == null ? 4 : declarationLines(contentWidth).size();
        return SyncSelectionLayout.forSize(width, height, 15 + lines * 10 + 3, showProgress);
    }

    private List<FormattedCharSequence> declarationLines(int maxWidth) {
        List<FormattedCharSequence> lines = new ArrayList<>();
        lines.addAll(font.split(Component.translatable("jane.select.declaration1"), maxWidth));
        lines.addAll(font.split(Component.translatable("jane.select.declaration2"), maxWidth));
        return lines;
    }

    private void drawDeclaration(GuiGraphics graphics, SyncSelectionLayout layout) {
        int left = layout.listLeft();
        int right = layout.listRight();
        graphics.fill(left, layout.declarationTop(), right, layout.declarationBottom(), 0x88000000);
        List<FormattedCharSequence> lines = declarationLines(right - left - 12);
        int contentHeight = lines.size() * 10;
        int textTop = layout.declarationTop() + 14;
        int visibleHeight = layout.declarationBottom() - textTop;
        declarationScroll = Math.max(0, Math.min(declarationScroll, Math.max(0, contentHeight - visibleHeight)));
        graphics.drawString(font, fit(Component.translatable("jane.select.declaration_title").getString(),
                right - left - 10), left + 5, layout.declarationTop() + 3, 0xFFCC77);
        graphics.enableScissor(left, textTop, right + 8, layout.declarationBottom());
        int y = textTop - declarationScroll;
        for (FormattedCharSequence line : lines) {
            graphics.drawString(font, line, left + 5, y, 0xBBBBBB);
            y += 10;
        }
        graphics.disableScissor();
        if (contentHeight > visibleHeight) {
            int thumb = Math.min(visibleHeight, Math.max(5, visibleHeight * visibleHeight / contentHeight));
            int thumbY = textTop + declarationScroll * (visibleHeight - thumb)
                    / (contentHeight - visibleHeight);
            graphics.fill(right + 3, textTop, right + 6, layout.declarationBottom(), 0xFF444444);
            graphics.fill(right + 3, thumbY, right + 6, thumbY + thumb, 0xFFAAAAAA);
        }
    }

    private void drawProgress(GuiGraphics graphics, JaneSyncSession.Snapshot snapshot,
                              SyncSelectionLayout layout) {
        int y = layout.progressTop();
        if (height <= 160) {
            drawBar(graphics, 39, snapshot.resolution() == null ? snapshot.resolutionPercent()
                    : snapshot.overallProgressPercent(), 4);
            return;
        }
        if (snapshot.resolution() == null) {
            graphics.drawCenteredString(font, fit(Component.translatable("jane.sync.resolving_progress",
                    snapshot.resolutionProcessed(), snapshot.resolutionTotal(), snapshot.resolutionPercent()).getString(),
                    width - 16), width / 2, y, 0xFFCC77);
            drawBar(graphics, y + (layout.compact() ? 13 : 21), snapshot.resolutionPercent(), 5);
            return;
        }
        JaneSyncSession.ItemState current = snapshot.items().stream().filter(item ->
                selection.isSelected(item.item().comparison().required().modId())
                        && (item.state() == JaneSyncSession.RuntimeState.DOWNLOADING
                        || item.state() == JaneSyncSession.RuntimeState.VERIFYING
                        || item.state() == JaneSyncSession.RuntimeState.RESOLVING)).findFirst().orElse(null);
        int total = selection.selectedUnmatchedModIds().size();
        String name = current == null ? Component.translatable("jane.sync.preparing_item").getString()
                : current.item().comparison().required().displayName();
        int ordinal = Math.min(total, (int) snapshot.readyCount() + 1);
        graphics.drawCenteredString(font, fit(Component.translatable("jane.sync.current_item", name,
                ordinal, total).getString(), width - 16), width / 2, y, 0xFFFFFF);
        String route = Component.translatable(snapshot.activeRoute() == ResolutionPlan.TransferRoute.CURRENT_SERVER
                ? "jane.details.current_server" : "jane.details.trusted_source").getString();
        graphics.drawCenteredString(font, fit(Component.translatable("jane.sync.route_progress", route,
                snapshot.overallProgressPercent()).getString(), width - 16), width / 2, y + 10, 0xFFCC77);
        drawBar(graphics, y + 21, snapshot.overallProgressPercent(), 5);
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
        if (launching) return;
        Button first = downloadButton;
        Button second = null;
        if (snapshot.running()) {
            first = cancelTransferButton;
            cancelTransferButton.active = activeTransferCancel != null && !activeTransferCancel.get();
        } else if (ready != null) {
            first = finishButton;
            finishButton.active = snapshot.canInstall();
        } else {
            downloadButton.setMessage(Component.translatable(resolved
                    ? "jane.sync.download_needed" : "jane.sync.resolving_button"));
            downloadButton.active = resolved && !retryingLookup && snapshot.error() == null
                    && snapshot.localErrorCount() == 0 && selectedPendingCount(snapshot) > 0
                    && (trustedSourceEnabled && snapshot.hasWaiting(ResolutionPlan.TransferGroup.TRUSTED)
                    || usableServerSource() && (snapshot.hasWaiting(ResolutionPlan.TransferGroup.TRUSTED)
                    || snapshot.hasWaiting(ResolutionPlan.TransferGroup.SERVER_ONLY)));
            boolean lookupFailed = snapshot.resolution() != null
                    && snapshot.resolution().count(ResolutionPlan.Availability.LOOKUP_FAILED) > 0;
            if (resolved && lookupFailed) second = retryButton;
            else if (canAttemptOverride()) second = skipButton;
            retryButton.active = !retryingLookup;
        }
        placeMain(first, second);
    }

    private void placeMain(Button first, Button second) {
        if (first == null) return;
        SyncSelectionLayout layout = SyncSelectionLayout.forSize(width, height);
        first.visible = true;
        if (second == null) {
            first.setX(layout.mainX());
            first.setWidth(layout.mainWidth());
        } else {
            int half = (layout.mainWidth() - 6) / 2;
            first.setX(layout.mainX());
            first.setWidth(half);
            second.setX(layout.mainX() + half + 6);
            second.setWidth(half);
            second.visible = true;
        }
    }

    private Component statusLine(JaneSyncSession.Snapshot snapshot) {
        if (snapshot.running()) {
            if (height > 160) return Component.translatable("jane.sync.downloading_needed");
            JaneSyncSession.ItemState current = snapshot.items().stream().filter(item ->
                    selection.isSelected(item.item().comparison().required().modId())
                            && (item.state() == JaneSyncSession.RuntimeState.DOWNLOADING
                            || item.state() == JaneSyncSession.RuntimeState.VERIFYING
                            || item.state() == JaneSyncSession.RuntimeState.RESOLVING)).findFirst().orElse(null);
            int total = selection.selectedUnmatchedModIds().size();
            String route = Component.translatable(snapshot.activeRoute() == ResolutionPlan.TransferRoute.CURRENT_SERVER
                    ? "jane.details.current_server" : "jane.details.trusted_source").getString();
            String name = current == null ? Component.translatable("jane.sync.preparing_item").getString()
                    : current.item().comparison().required().displayName();
            return Component.translatable("jane.sync.compact_progress", route, name,
                    Math.min(total, (int) snapshot.readyCount() + 1), total, snapshot.overallProgressPercent());
        }
        if (ready != null) return Component.translatable("jane.sync.selected_completed");
        if (snapshot.resolution() == null) return Component.translatable("jane.sync.resolving_progress",
                snapshot.resolutionProcessed(), snapshot.resolutionTotal(), snapshot.resolutionPercent());
        if (retryingLookup) return Component.translatable("jane.sync.resolving");
        if (downloadPaused) return Component.translatable("jane.sync.download_paused");
        if (!trustedSourceEnabled && !usableServerSource()) return Component.translatable("jane.sync.no_source");
        return Component.translatable(width < 300 || height <= 190
                        ? "jane.sync.source_counts_compact" : "jane.sync.source_counts",
                snapshot.resolution().count(ResolutionPlan.Availability.TRUSTED_AVAILABLE),
                snapshot.resolution().count(ResolutionPlan.Availability.SERVER_ONLY),
                snapshot.resolution().count(ResolutionPlan.Availability.LOOKUP_FAILED));
    }

    private List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        List<Comparison.Result> comparisons = session.context().results();
        Map<String, ClientInstallCategory> categories = selection.categories();
        if (ready != null) {
            addNote(rows, "jane.sync.confirm_move_hint");
            addNote(rows, "jane.sync.confirm_line2");
        }
        rows.add(new Row(RowKind.REQUIRED_HEADER, 22, null, null));
        if (requiredOpen) {
            for (Comparison.Result result : SyncSelectionLayout.pendingRows(comparisons, categories,
                    ClientInstallCategory.SERVER_REQUIRED)) rows.add(new Row(RowKind.MOD, 42, result, null));
            rows.add(new Row(RowKind.BULK_REQUIRED, 22, null, null));
        }
        rows.add(new Row(RowKind.MATCHED_HEADER, 22, null, null));
        if (matchedOpen) for (Comparison.Result result : SyncSelectionLayout.matchedRows(comparisons))
            rows.add(new Row(RowKind.MOD, 42, result, null));
        rows.add(new Row(RowKind.OPTIONAL_HEADER, 22, null, null));
        if (optionalOpen) for (Comparison.Result result : SyncSelectionLayout.pendingRows(comparisons,
                categories, ClientInstallCategory.CLIENT_OPTIONAL))
            rows.add(new Row(RowKind.MOD, 42, result, null));
        rows.add(new Row(RowKind.SOURCE_HEADER, 22, null, null));
        if (sourceOpen) {
            rows.add(new Row(RowKind.SOURCE_TRUSTED, 22, null, null));
            rows.add(new Row(RowKind.SOURCE_SERVER, 22, null, null));
            if (session.context().provider() == null) addNote(rows, "jane.sync.server_source_unavailable");
        }
        return rows;
    }

    private void addNote(List<Row> rows, String key) {
        SyncSelectionLayout layout = layout(false);
        int maxWidth = Math.max(90, layout.listRight() - layout.listLeft() - 14);
        for (FormattedCharSequence line : font.split(Component.translatable(key), maxWidth))
            rows.add(new Row(RowKind.NOTE, 12, null, line));
    }

    private int contentHeight(List<Row> rows) { return rows.stream().mapToInt(Row::height).sum(); }

    private void clampScroll(List<Row> rows) {
        SyncSelectionLayout layout = layout(session.snapshot().running() || session.snapshot().resolution() == null);
        scroll = Math.max(0, Math.min(scroll, Math.max(0,
                contentHeight(rows) - (layout.listBottom() - layout.listTop()))));
    }

    private void drawRow(GuiGraphics graphics, JaneSyncSession.Snapshot snapshot, Row row, int y,
                         int mouseX, int mouseY, SyncSelectionLayout layout) {
        int left = layout.listLeft();
        int right = layout.listRight();
        if (row.kind() == RowKind.NOTE) {
            graphics.drawString(font, row.text(), left + 5, y + 1, 0xBBBBBB);
            return;
        }
        boolean hover = mouseX >= left && mouseX < right && mouseY >= y && mouseY < y + row.height();
        graphics.fill(left, y, right, y + row.height() - 2, hover ? 0xAA444444 : 0x88000000);
        if (row.kind() == RowKind.BULK_REQUIRED) {
            int half = (right - left) / 2;
            graphics.drawString(font, fit(Component.translatable("jane.select.select_all").getString(), half - 10),
                    left + 5, y + 6, 0xAAFFAA);
            graphics.drawString(font, fit(Component.translatable("jane.select.select_none").getString(), half - 10),
                    left + half + 5, y + 6, 0xFFCC77);
            return;
        }
        if (row.kind() == RowKind.SOURCE_TRUSTED || row.kind() == RowKind.SOURCE_SERVER) {
            boolean server = row.kind() == RowKind.SOURCE_SERVER;
            boolean available = !server || session.context().provider() != null;
            boolean enabled = server ? serverSourceEnabled : trustedSourceEnabled;
            String checkbox = enabled ? "☑ " : "☐ ";
            String label = Component.translatable(server ? "jane.sync.source_server" : "jane.sync.source_trusted")
                    .getString();
            graphics.drawString(font, fit(checkbox + label, right - left - 12), left + 6, y + 6,
                    available && !snapshot.running() && downloadCoordinator == null ? 0xFFFFFF : 0x999999);
            return;
        }
        if (row.kind() != RowKind.MOD) {
            String key = switch (row.kind()) {
                case REQUIRED_HEADER -> "jane.select.required_group";
                case MATCHED_HEADER -> "jane.sync.matched_group";
                case OPTIONAL_HEADER -> "jane.select.optional_group";
                case SOURCE_HEADER -> "jane.sync.source_settings";
                default -> throw new IllegalStateException("Unexpected row type");
            };
            boolean open = row.kind() == RowKind.REQUIRED_HEADER ? requiredOpen
                    : row.kind() == RowKind.OPTIONAL_HEADER ? optionalOpen
                    : row.kind() == RowKind.MATCHED_HEADER ? matchedOpen : sourceOpen;
            long count = row.kind() == RowKind.SOURCE_HEADER ? -1 : session.context().results().stream()
                    .filter(result -> row.kind() == RowKind.MATCHED_HEADER
                            ? result.status() == Comparison.Status.OK
                            : result.status() != Comparison.Status.OK
                            && selection.category(result.required().modId()) == (row.kind() == RowKind.REQUIRED_HEADER
                            ? ClientInstallCategory.SERVER_REQUIRED : ClientInstallCategory.CLIENT_OPTIONAL)).count();
            String label = (open ? "▼ " : "▶ ") + Component.translatable(key).getString();
            if (count >= 0) label += Component.translatable(row.kind() == RowKind.REQUIRED_HEADER
                    ? "jane.sync.pending_count" : "jane.sync.group_count", count).getString();
            graphics.drawString(font, fit(label, right - left - 10), left + 5, y + 6, 0xFFFFFF);
            return;
        }
        Comparison.Result result = row.comparison();
        String modId = result.required().modId();
        boolean selected = selection.isSelected(modId);
        int textLeft = left + 25;
        graphics.drawString(font, result.status() == Comparison.Status.OK ? "✓"
                : selected ? "☑" : "☐", left + 6, y + 13,
                result.status() == Comparison.Status.OK ? 0xAAFFAA
                        : (!session.resolutionStarted() || resolved) && !snapshot.started() && !snapshot.running()
                        ? 0xFFFFFF : 0x999999);
        graphics.drawString(font, fit(result.required().displayName(), right - textLeft - 8),
                textLeft, y + 3, 0xFFFFFF);
        Component status = Component.translatable("jane.status." + result.status().name().toLowerCase(Locale.ROOT));
        int statusWidth = Math.min(95, font.width(status) + 2);
        graphics.drawString(font, fit(modId + " · " + result.required().version(),
                right - textLeft - statusWidth - 10), textLeft, y + 15, 0xBBBBBB);
        graphics.drawString(font, fit(status.getString(), statusWidth), right - statusWidth - 5,
                y + 15, result.status() == Comparison.Status.OK ? 0xAAFFAA : 0xFFCC77);
        String source = sourceLabel(snapshot, result);
        String size = fit(mib(result.required().fileSize()) + " MiB", Math.max(30, (right - textLeft) / 2));
        graphics.drawString(font, fit(source, right - textLeft - font.width(size) - 12),
                textLeft, y + 28, 0xAAAAAA);
        graphics.drawString(font, size, right - 5 - font.width(size), y + 28, 0xAAAAAA);
    }

    private String sourceLabel(JaneSyncSession.Snapshot snapshot, Comparison.Result result) {
        if (result.status() == Comparison.Status.OK) return Component.translatable("jane.details.matched").getString();
        if (snapshot.resolution() == null) return Component.translatable("jane.details.source_pending").getString();
        for (JaneSyncSession.ItemState state : snapshot.items()) {
            if (!state.item().comparison().required().modId().equals(result.required().modId())) continue;
            if (state.state() == JaneSyncSession.RuntimeState.DOWNLOADING)
                return Component.translatable("jane.runtime.downloading",
                        (int) (100 * state.downloadedBytes() / result.required().fileSize())).getString();
            if (state.state() != null && state.state() != JaneSyncSession.RuntimeState.WAITING)
                return Component.translatable("jane.runtime." + state.state().name().toLowerCase(Locale.ROOT)).getString();
            return Component.translatable(switch (state.item().availability()) {
                case TRUSTED_AVAILABLE -> "jane.details.trusted_source";
                case SERVER_ONLY -> "jane.details.current_server";
                case ALREADY_PRESENT -> "jane.details.matched";
                case LOOKUP_FAILED -> "jane.sync.lookup_failed";
                case UNRESOLVED -> "jane.details.unknown_source";
                case LOCAL_ERROR -> "jane.sync.local_error";
            }).getString();
        }
        return Component.translatable("jane.details.source_pending").getString();
    }

    private String fit(String value, int maxWidth) {
        if (maxWidth <= 0) return "";
        if (font.width(value) <= maxWidth) return value;
        return font.plainSubstrByWidth(value, Math.max(0, maxWidth - font.width("…"))) + "…";
    }
    private void drawBar(GuiGraphics graphics, int y, int percent, int barHeight) {
        int barWidth = Math.min(220, width - 40);
        int barX = (width - barWidth) / 2;
        graphics.fill(barX, y, barX + barWidth, y + barHeight, 0xFF555555);
        graphics.fill(barX, y, barX + barWidth * percent / 100, y + barHeight, 0xFF55AA55);
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        SyncSelectionLayout layout = layout(session.snapshot().running() || session.snapshot().resolution() == null);
        if (mouseY >= layout.declarationTop() && mouseY < layout.declarationBottom()) {
            declarationScroll -= (int) Math.round(amount * 16);
            return true;
        }
        if (mouseY < layout.listTop() || mouseY >= layout.listBottom())
            return super.mouseScrolled(mouseX, mouseY, amount);
        scroll -= (int) Math.round(amount * 24);
        clampScroll(rows());
        return true;
    }

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        SyncSelectionLayout layout = layout(session.snapshot().running() || session.snapshot().resolution() == null);
        if (button == 0 && mouseY >= layout.declarationTop() && mouseY < layout.declarationBottom()
                && mouseX >= layout.listRight() + 3 && mouseX <= layout.listRight() + 8) {
            draggingDeclarationBar = true;
            return true;
        }
        if (button != 0 || mouseY < layout.listTop() || mouseY >= layout.listBottom()) return false;
        List<Row> rows = rows();
        if (mouseX >= layout.listRight() + 3 && mouseX <= layout.listRight() + 9
                && contentHeight(rows) > layout.listBottom() - layout.listTop()) {
            draggingBar = true;
            return true;
        }
        if (mouseX < layout.listLeft() || mouseX >= layout.listRight()) return false;
        int y = layout.listTop() - scroll;
        for (Row row : rows) {
            if (mouseY >= y && mouseY < y + row.height()) {
                switch (row.kind()) {
                    case REQUIRED_HEADER -> requiredOpen = !requiredOpen;
                    case MATCHED_HEADER -> matchedOpen = !matchedOpen;
                    case OPTIONAL_HEADER -> optionalOpen = !optionalOpen;
                    case SOURCE_HEADER -> sourceOpen = !sourceOpen;
                    case SOURCE_TRUSTED -> toggleSource(false);
                    case SOURCE_SERVER -> toggleSource(true);
                    case BULK_REQUIRED -> bulkRequired(mouseX < (layout.listLeft() + layout.listRight()) / 2);
                    case MOD -> toggle(row.comparison());
                    case NOTE -> { return false; }
                }
                clampScroll(rows());
                return true;
            }
            y += row.height();
        }
        return false;
    }

    @Override public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingDeclarationBar && button == 0) {
            SyncSelectionLayout layout = layout(session.snapshot().running() || session.snapshot().resolution() == null);
            int visible = layout.declarationBottom() - layout.declarationTop() - 14;
            int total = declarationLines(layout.listRight() - layout.listLeft() - 12).size() * 10;
            if (total > visible) declarationScroll += (int) Math.round(dragY * total / Math.max(1.0, visible));
            return true;
        }
        if (draggingBar && button == 0) {
            SyncSelectionLayout layout = layout(session.snapshot().running() || session.snapshot().resolution() == null);
            List<Row> rows = rows();
            int view = layout.listBottom() - layout.listTop();
            int total = contentHeight(rows);
            if (total > view) {
                int thumb = Math.max(12, view * view / total);
                scroll += (int) Math.round(dragY * (total - view) / Math.max(1.0, view - thumb));
                clampScroll(rows);
            }
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override public boolean mouseReleased(double mouseX, double mouseY, int button) {
        draggingBar = false;
        draggingDeclarationBar = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void toggleSource(boolean server) {
        if (session.snapshot().running() || downloadCoordinator != null || launching || ready != null
                || server && session.context().provider() == null) return;
        if (server) serverSourceEnabled = !serverSourceEnabled;
        else trustedSourceEnabled = !trustedSourceEnabled;
        audit("DOWNLOAD_SOURCE_SETTING", Map.of("reason", server ? "CURRENT_SERVER" : "TRUSTED_SOURCE",
                "status", Boolean.toString(server ? serverSourceEnabled : trustedSourceEnabled)));
        notice = !trustedSourceEnabled && !usableServerSource()
                ? Component.translatable("jane.sync.no_source") : null;
    }

    private void toggle(Comparison.Result result) {
        JaneSyncSession.Snapshot snapshot = session.snapshot();
        if (result.status() == Comparison.Status.OK) return;
        if ((session.resolutionStarted() && !resolved) || snapshot.running() || snapshot.started()
                || retryingLookup || launching || ready != null) return;
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

    private void bulkRequired(boolean select) {
        JaneSyncSession.Snapshot snapshot = session.snapshot();
        if ((session.resolutionStarted() && !resolved) || snapshot.running() || snapshot.started()
                || retryingLookup || launching || ready != null) return;
        for (Comparison.Result result : session.context().results()) {
            if (result.status() != Comparison.Status.OK
                    && selection.category(result.required().modId()) == ClientInstallCategory.SERVER_REQUIRED
                    && selection.isSelected(result.required().modId()) != select) toggle(result);
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
