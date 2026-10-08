package dev.modsbyfox.jane.fabric.client;

import dev.modsbyfox.jane.core.Comparison;
import dev.modsbyfox.jane.core.ClientInstallCategory;
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
    private boolean declarationOpen;
    private int scroll;
    private boolean draggingBar;
    private final Set<String> auditedOptionals = new HashSet<>();
    private UpdatePlan ready;
    private Component notice;
    private Button trustedButton;
    private Button serverRouteButton;
    private Button serverOnlyButton;
    private Button retryButton;
    private Button cancelTransferButton;
    private Button finishButton;
    private Button skipButton;
    private Button detailsButton;
    private Button cancelButton;

    private enum RowKind { REQUIRED_HEADER, OPTIONAL_HEADER, DECLARATION_HEADER, BULK_REQUIRED, NOTE, MOD }
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
        SyncSelectionLayout layout = SyncSelectionLayout.forSize(width, height);
        int x = layout.mainX();
        int buttonWidth = layout.mainWidth();
        trustedButton = addRenderableWidget(Button.builder(Component.translatable("jane.sync.resolve_files"), button -> {
            if (!session.resolutionStarted()) beginResolve();
            else start(ResolutionPlan.TransferGroup.TRUSTED, ResolutionPlan.TransferRoute.TRUSTED_SOURCE, false);
        }).bounds(x, layout.mainY(), buttonWidth, 20).build());
        serverRouteButton = addRenderableWidget(Button.builder(Component.translatable("jane.sync.download_server_route"),
                button -> start(ResolutionPlan.TransferGroup.TRUSTED, ResolutionPlan.TransferRoute.CURRENT_SERVER, false))
                .bounds(x, layout.mainY(), buttonWidth, 20).build());
        serverOnlyButton = addRenderableWidget(Button.builder(Component.translatable("jane.sync.review_server_only"),
                button -> minecraft.setScreen(new ServerDownloadConfirmScreen(this, session)))
                .bounds(x, layout.mainY(), buttonWidth, 20).build());
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
    }

    private void beginResolve() {
        if (sessionCancelled.get() || !session.beginResolution(session.context().results().size())) return;
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
        start(ResolutionPlan.TransferGroup.SERVER_ONLY, ResolutionPlan.TransferRoute.CURRENT_SERVER, true);
    }

    private void start(ResolutionPlan.TransferGroup group, ResolutionPlan.TransferRoute route, boolean confirmed) {
        if (sessionCancelled.get() || retryingLookup) return;
        JaneSyncSession.Snapshot before = session.snapshot();
        boolean started = confirmed ? session.startServerOnlyConfirmed() : session.startTransfer(group, route);
        if (!started) return;
        selection.freezeChoices();
        audit("DOWNLOAD_CHOICE", Map.of("reason", route.name(),
                "selectedCount", Integer.toString(selection.selectedUnmatchedModIds().size()),
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
                notice = noticeFor(snapshot);
            } else if (error != null) {
                LOGGER.error("{}staging failed group={} route={}", JaneLog.client(session.context().serverId()),
                        group, route, error);
                session.finishTransfer(false, "staging");
                notice = Component.translatable("jane.sync.failed");
            } else {
                session.finishTransfer(false, null);
                ready = outcome.plan();
                if (ready != null) scroll = 0;
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
        SyncSelectionLayout layout = SyncSelectionLayout.forSize(width, height);
        graphics.drawCenteredString(font, title, width / 2, layout.compact() ? 5 : 5, 0xFFFFFF);
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
        if (snapshot.running() || (session.resolutionStarted() && snapshot.resolution() == null))
            drawBar(graphics, layout.compact() ? 40 : 53,
                    snapshot.running() ? snapshot.progressPercent() : snapshot.resolutionPercent(),
                    layout.compact() ? 3 : 8);
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
        Component footer = auditWarning ? Component.translatable("jane.select.audit_incomplete")
                : snapshot.running() ? null
                : ready != null ? Component.translatable("jane.sync.confirm_move_hint") : notice;
        if (footer != null && !layout.compact())
            graphics.drawCenteredString(font, fit(footer.getString(), width - 16), width / 2,
                    layout.listBottom() + 2, auditWarning ? 0xFF7777 : ready != null ? 0xAAAAAA : 0xFFCC77);
        super.render(graphics, mouseX, mouseY, partialTick);
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
        trustedButton.visible = serverRouteButton.visible = serverOnlyButton.visible = retryButton.visible = false;
        cancelTransferButton.visible = finishButton.visible = skipButton.visible = false;
        if (retryingLookup || launching) return;
        Button first = null;
        Button second = null;
        if (!session.resolutionStarted()) {
            first = canAttemptOverride() ? skipButton : trustedButton;
            trustedButton.setMessage(Component.translatable("jane.sync.resolve_files"));
        } else if (snapshot.running()) {
            first = cancelTransferButton;
            cancelTransferButton.active = activeTransferCancel != null && !activeTransferCancel.get();
        } else if (ready != null) {
            first = finishButton;
            finishButton.active = snapshot.canInstall();
        } else if (resolved && snapshot.resolution() != null) {
            boolean lookupFailed = snapshot.items().stream().anyMatch(item ->
                    selection.isSelected(item.item().comparison().required().modId())
                            && item.item().availability() == ResolutionPlan.Availability.LOOKUP_FAILED);
            if (snapshot.hasWaiting(ResolutionPlan.TransferGroup.TRUSTED)) {
                first = trustedButton;
                trustedButton.setMessage(Component.translatable("jane.select.trusted_short"));
                if (session.context().provider() != null) {
                    second = serverRouteButton;
                    serverRouteButton.setMessage(Component.translatable("jane.select.server_short"));
                } else if (lookupFailed) second = retryButton;
            } else if (snapshot.groupReady(ResolutionPlan.TransferGroup.TRUSTED)
                    && snapshot.hasWaiting(ResolutionPlan.TransferGroup.SERVER_ONLY)) {
                first = serverOnlyButton;
                if (lookupFailed) second = retryButton;
            } else if (lookupFailed) first = retryButton;
            else if (canAttemptOverride()) first = skipButton;
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
        if (snapshot.running()) return Component.translatable("jane.select.progress", snapshot.processedCount(),
                snapshot.batchTotal(), snapshot.progressPercent());
        if (ready != null) return Component.translatable("jane.select.ready_to_restart",
                selection.selectedUnmatchedModIds().size());
        if (!session.resolutionStarted()) return Component.translatable("jane.select.start_resolution");
        if (snapshot.resolution() == null) return Component.translatable("jane.sync.resolving_progress",
                snapshot.resolutionProcessed(), snapshot.resolutionTotal(), snapshot.resolutionPercent());
        return Component.translatable("jane.select.summary", selection.selectedUnmatchedModIds().size(),
                skippedItems().size());
    }

    private List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        if (ready != null) {
            addNote(rows, "jane.sync.confirm_move_hint");
            addNote(rows, "jane.sync.confirm_line2");
        }
        rows.add(new Row(RowKind.REQUIRED_HEADER, 22, null, null));
        if (requiredOpen) {
            addNote(rows, "jane.select.required_hint");
            rows.add(new Row(RowKind.BULK_REQUIRED, 22, null, null));
            for (Comparison.Result result : session.context().results())
                if (selection.category(result.required().modId()) == ClientInstallCategory.SERVER_REQUIRED)
                    rows.add(new Row(RowKind.MOD, 42, result, null));
            rows.add(new Row(RowKind.DECLARATION_HEADER, 21, null, null));
            if (declarationOpen) {
                addNote(rows, "jane.select.declaration1");
                addNote(rows, "jane.select.declaration2");
                addNote(rows, "jane.select.declaration3");
            }
        }
        rows.add(new Row(RowKind.OPTIONAL_HEADER, 22, null, null));
        if (optionalOpen) {
            addNote(rows, "jane.select.optional_hint");
            boolean any = false;
            for (Comparison.Result result : session.context().results())
                if (selection.category(result.required().modId()) == ClientInstallCategory.CLIENT_OPTIONAL) {
                    rows.add(new Row(RowKind.MOD, 42, result, null));
                    any = true;
                }
            if (!any) addNote(rows, "jane.select.optional_empty");
        }
        return rows;
    }

    private void addNote(List<Row> rows, String key) {
        int maxWidth = Math.max(90, SyncSelectionLayout.forSize(width, height).listRight()
                - SyncSelectionLayout.forSize(width, height).listLeft() - 14);
        for (FormattedCharSequence line : font.split(Component.translatable(key), maxWidth))
            rows.add(new Row(RowKind.NOTE, 12, null, line));
    }

    private int contentHeight(List<Row> rows) { return rows.stream().mapToInt(Row::height).sum(); }

    private void clampScroll(List<Row> rows) {
        SyncSelectionLayout layout = SyncSelectionLayout.forSize(width, height);
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
        if (row.kind() != RowKind.MOD) {
            String key = row.kind() == RowKind.REQUIRED_HEADER ? "jane.select.required_group"
                    : row.kind() == RowKind.OPTIONAL_HEADER ? "jane.select.optional_group" : "jane.select.declaration_title";
            boolean open = row.kind() == RowKind.REQUIRED_HEADER ? requiredOpen
                    : row.kind() == RowKind.OPTIONAL_HEADER ? optionalOpen : declarationOpen;
            String label = (open ? "▼ " : "▶ ") + Component.translatable(key).getString();
            graphics.drawString(font, fit(label, right - left - 42), left + 5, y + 6, 0xFFFFFF);
            if (row.kind() != RowKind.DECLARATION_HEADER) {
                long count = session.context().results().stream().filter(result -> selection.category(
                        result.required().modId()) == (row.kind() == RowKind.REQUIRED_HEADER
                        ? ClientInstallCategory.SERVER_REQUIRED : ClientInstallCategory.CLIENT_OPTIONAL)).count();
                graphics.drawString(font, Long.toString(count), right - 24, y + 6, 0xFFFFFF);
            }
            return;
        }
        Comparison.Result result = row.comparison();
        String modId = result.required().modId();
        boolean selected = selection.isSelected(modId);
        int textLeft = left + 25;
        graphics.drawString(font, selected ? "☑" : "☐", left + 6, y + 13,
                (!session.resolutionStarted() || resolved) && !snapshot.started() && !snapshot.running()
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
        graphics.drawString(font, fit(source, right - textLeft - 62), textLeft, y + 28, 0xAAAAAA);
        String size = mib(result.required().fileSize()) + " MiB";
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
        SyncSelectionLayout layout = SyncSelectionLayout.forSize(width, height);
        if (mouseY < layout.listTop() || mouseY >= layout.listBottom())
            return super.mouseScrolled(mouseX, mouseY, amount);
        scroll -= (int) Math.round(amount * 24);
        clampScroll(rows());
        return true;
    }

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        SyncSelectionLayout layout = SyncSelectionLayout.forSize(width, height);
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
                    case OPTIONAL_HEADER -> optionalOpen = !optionalOpen;
                    case DECLARATION_HEADER -> declarationOpen = !declarationOpen;
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
        if (draggingBar && button == 0) {
            SyncSelectionLayout layout = SyncSelectionLayout.forSize(width, height);
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
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void toggle(Comparison.Result result) {
        JaneSyncSession.Snapshot snapshot = session.snapshot();
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
