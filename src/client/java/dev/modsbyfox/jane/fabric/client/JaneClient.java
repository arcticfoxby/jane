package dev.modsbyfox.jane.fabric.client;

import dev.modsbyfox.jane.core.Comparison;
import dev.modsbyfox.jane.core.ClientCompatibilityReport;
import dev.modsbyfox.jane.core.ManifestCodec;
import dev.modsbyfox.jane.core.LoginStatus;
import dev.modsbyfox.jane.core.OneShotJoinOverride;
import dev.modsbyfox.jane.core.OneShotExtraJoin;
import dev.modsbyfox.jane.core.PendingSyncContext;
import dev.modsbyfox.jane.core.PendingRecovery;
import dev.modsbyfox.jane.core.RequiredManifest;
import dev.modsbyfox.jane.core.ServerIdentity;
import dev.modsbyfox.jane.core.SyncDecisionAudit;
import dev.modsbyfox.jane.core.SyncSelection;
import dev.modsbyfox.jane.fabric.JaneLog;
import dev.modsbyfox.jane.fabric.JaneMod;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraft.network.FriendlyByteBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class JaneClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("jane");
    private static final PendingClientActions PENDING = new PendingClientActions();
    private static final OneShotJoinOverride OVERRIDE = new OneShotJoinOverride();
    private static final OneShotExtraJoin EXTRA_JOIN = new OneShotExtraJoin();
    private static volatile long hudUntil;
    private static boolean startupChecked;

    @Override
    public void onInitializeClient() {
        LOGGER.info("{}startup protocol={}", JaneLog.clientStartup(), RequiredManifest.PROTOCOL);
        ClientLoginConnectionEvents.INIT.register((handler, client) -> {
            PENDING.begin(handler);
            OVERRIDE.beginConnection(handler);
            EXTRA_JOIN.beginConnection(handler);
        });
        ClientLoginNetworking.registerGlobalReceiver(JaneMod.MANIFEST_CHANNEL, (client, handler, buf, listenerAdder) -> {
            if (buf.readableBytes() > RequiredManifest.MAX_PAYLOAD) {
                PENDING.clearForConnection(handler);
                OVERRIDE.clearForConnection(handler);
                EXTRA_JOIN.clearForConnection(handler);
                return CompletableFuture.completedFuture(response(LoginStatus.PROTOCOL_ERROR));
            }
            byte[] payload = new byte[buf.readableBytes()];
            buf.readBytes(payload);
            final String serverAddress;
            final ServerData serverData;
            try {
                serverData = client.isLocalServer() || client.hasSingleplayerServer()
                        ? null : LoginServerAddress.captureData(handler);
                serverAddress = serverData == null ? null : ServerIdentity.normalize(serverData.ip);
            } catch (IOException exception) {
                PENDING.clearForConnection(handler);
                OVERRIDE.clearForConnection(handler);
                EXTRA_JOIN.clearForConnection(handler);
                LOGGER.error("Jane could not capture the server address during login", exception);
                return CompletableFuture.completedFuture(response(LoginStatus.PROTOCOL_ERROR));
            }
            return CompletableFuture.supplyAsync(() -> {
                try {
                    ManifestCodec.LoginOffer offer = ManifestCodec.decodeOffer(payload);
                    RequiredManifest manifest = offer.manifest();
                    ClientAssessment assessment = ClientAssessmentService.assess(
                            FabricLoader.getInstance().getGameDir(), manifest);
                    List<Comparison.Result> results = assessment.requiredResults();
                    String prefix = serverAddress == null ? JaneLog.clientStartup()
                            : JaneLog.client(ServerIdentity.id(serverAddress));
                    long missing = results.stream().filter(result -> result.status() == Comparison.Status.MISSING).count();
                    long versionMismatch = results.stream()
                            .filter(result -> result.status() == Comparison.Status.VERSION_MISMATCH).count();
                    long hashMismatch = results.stream()
                            .filter(result -> result.status() == Comparison.Status.HASH_MISMATCH).count();
                    long fileError = results.stream()
                            .filter(result -> result.status() == Comparison.Status.FILE_ERROR).count();
                    LOGGER.info("{}required comparison completed required={} missing={} versionMismatch={} "
                                    + "hashMismatch={} fileError={}", prefix, results.size(), missing,
                            versionMismatch, hashMismatch, fileError);
                    boolean auditSaveFailed = false;
                    if (serverAddress != null) {
                        try {
                            SyncDecisionAudit.record(FabricLoader.getInstance().getGameDir(),
                                    "REQUIRED_COMPARISON_COMPLETED", Map.of(
                                            "serverId", ServerIdentity.id(serverAddress),
                                            "manifestDigest", OneShotJoinOverride.manifestDigest(manifest),
                                            "required", Integer.toString(results.size()),
                                            "missing", Long.toString(missing),
                                            "versionMismatch", Long.toString(versionMismatch),
                                            "fileError", Long.toString(fileError)));
                        } catch (IOException | SecurityException exception) {
                            auditSaveFailed = true;
                            LOGGER.error("{}sync decision audit could not save required comparison cause={}",
                                    prefix, exception.getClass().getSimpleName());
                        }
                    }
                    for (Comparison.Result result : results) {
                        if (!result.retainedCandidates().isEmpty())
                            LOGGER.info("{}comparison modId={} status={} exactFile={} retainedDuplicates={} installPolicy={}",
                                    prefix, result.required().modId(), result.status(),
                                    result.local() == null ? "none" : result.local().jar().getFileName(),
                                    result.retainedCandidates().size(),
                                    result.local() == null ? "ADD_ALONGSIDE" : "PRESERVE_EXTRAS");
                        if (result.status() == Comparison.Status.OK) continue;
                        String reason = result.localIssue() == null ? "none" : result.localIssue().kind().name();
                        String candidates = result.localIssue() == null ? "[]" : result.localIssue().files().toString();
                        LOGGER.info("{}comparison modId={} status={} reason={} requiredVersion={} requiredHash={} "
                                        + "localVersion={} localHash={} localFile={} candidates={}", prefix,
                                result.required().modId(), result.status(), reason, result.required().version(),
                                result.required().sha512().substring(0, 12),
                                result.local() == null ? "none" : result.local().version(),
                                result.localHash() == null ? "none" : result.localHash().substring(0, 12),
                                result.local() == null ? "none" : result.local().jar().getFileName(), candidates);
                    }
                    ClientCompatibilityReport extras = assessment.compatibility();
                    int extraCount = extras == null ? 0
                            : extras.explicitClientMods().size() + extras.otherExtraMods().size();
                    boolean requiredPassed = Comparison.passed(results);
                    if (serverAddress != null) {
                        String serverId = ServerIdentity.id(serverAddress);
                        auditSaveFailed |= !recordAudit("ENVIRONMENT_CHECK_COMPLETED", serverId,
                                Map.of("manifestDigest", OneShotJoinOverride.manifestDigest(manifest),
                                        "required", Integer.toString(results.size()),
                                        "matched", Long.toString(results.size() - missing - versionMismatch
                                                - hashMismatch - fileError),
                                        "missing", Long.toString(missing),
                                        "versionMismatch", Long.toString(versionMismatch),
                                        "hashMismatch", Long.toString(hashMismatch),
                                        "fileError", Long.toString(fileError),
                                        "extraClientMods", Integer.toString(extraCount)));
                        if (extraCount > 0)
                            auditSaveFailed |= !recordAudit("EXTRA_CLIENT_MOD_WARNING", serverId,
                                    Map.of("extraClientMods", Integer.toString(extraCount),
                                            "requiredBaseline", requiredPassed ? "PASS" : "NOT_SATISFIED"));
                    }
                    LOGGER.info("{}environment check required={} matched={} unmet={} extraClientMods={} fileError={}",
                            prefix, results.size(), results.size() - missing - versionMismatch - hashMismatch
                                    - fileError, missing + versionMismatch + hashMismatch + fileError,
                            extraCount, fileError);
                    if (requiredPassed) {
                        OVERRIDE.clearForConnection(handler);
                        if (EnvironmentGate.route(results, extras) == EnvironmentGate.Route.EXACT_PASS) {
                            EXTRA_JOIN.clearForConnection(handler);
                            LOGGER.info("{}required baseline PASS; no extra client mods; loginStatus=EXACT_PASS", prefix);
                            return response(PENDING.markPassed(handler)
                                    ? LoginStatus.EXACT_PASS : LoginStatus.PROTOCOL_ERROR);
                        }
                        if (serverAddress == null) throw new IOException("Jane extra-mod decision is unavailable for a local world");
                        String serverId = ServerIdentity.id(serverAddress);
                        if (EXTRA_JOIN.consume(handler, serverId, manifest, extras)) {
                            LOGGER.info("{}playerAction=DIRECT_JOIN_WITH_EXTRAS requiredBaseline=PASS "
                                            + "extraClientMods={} preservedExtras={} loginStatus=EXACT_PASS",
                                    prefix, extraCount, extraCount);
                            return response(PENDING.markPassed(handler)
                                    ? LoginStatus.EXACT_PASS : LoginStatus.PROTOCOL_ERROR);
                        }
                    } else {
                        EXTRA_JOIN.clearForConnection(handler);
                        if (serverAddress == null) throw new IOException("Jane sync is unavailable for a local world");
                        String serverId = ServerIdentity.id(serverAddress);
                        if (OVERRIDE.consume(handler, serverId, manifest, results)) {
                            PENDING.clearForConnection(handler);
                            LOGGER.warn("{}requiredBaseline=NOT_SATISFIED joinAction=ATTEMPT_WITH_OVERRIDE "
                                    + "loginStatus=USER_OVERRIDE", prefix);
                            return response(LoginStatus.USER_OVERRIDE);
                        }
                    }
                    PendingSyncContext context = new PendingSyncContext(serverAddress, manifest, results, offer.provider());
                    if (!PENDING.replace(handler, new PendingClientAction.EnvironmentDecision(context,
                            extras, new ReconnectTarget(serverData), auditSaveFailed)))
                        return response(LoginStatus.PROTOCOL_ERROR);
                    LOGGER.info("{}environment decision pending serverId={} requiredPassed={} extraClientMods={}",
                            prefix, context.serverId().substring(0, 12), requiredPassed, extraCount);
                    return response(LoginStatus.ACTION_REQUIRED);
                } catch (IOException | IllegalArgumentException exception) {
                    PENDING.clearForConnection(handler);
                    OVERRIDE.clearForConnection(handler);
                    EXTRA_JOIN.clearForConnection(handler);
                    LOGGER.error("Jane login manifest handling failed", exception);
                    return response(LoginStatus.PROTOCOL_ERROR);
                }
            });
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.level == null && client.screen instanceof DisconnectedScreen) {
                PendingClientAction action = PENDING.take();
                if (action instanceof PendingClientAction.EnvironmentDecision decision)
                    client.setScreen(new EnvironmentDecisionScreen(client.screen, decision));
                else if (action instanceof PendingClientAction.RequiredSync sync)
                    client.setScreen(new SyncScreen(client.screen, sync.context(), sync.target(),
                            sync.auditSaveFailed()));
                else if (action instanceof PendingClientAction.CompatibilityReview review)
                    client.setScreen(new CompatibilityReviewScreen(client.screen, review));
            }
            if (!startupChecked && client.screen instanceof TitleScreen) {
                startupChecked = true;
                var parent = client.screen;
                CompletableFuture.supplyAsync(() -> {
                    try {
                        return PendingRecovery.scan(FabricLoader.getInstance().getGameDir());
                    } catch (IOException exception) {
                        throw new java.util.concurrent.CompletionException(exception);
                    }
                }).whenComplete((items, error) -> client.execute(() -> {
                    if (error != null) {
                        LOGGER.error("Jane pending recovery scan failed", error);
                    } else if (!items.isEmpty() && client.screen == parent) {
                        client.setScreen(new RecoveryScreen(parent, items.get(0)));
                    }
                }));
            }
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (PENDING.takePassed()) hudUntil = System.currentTimeMillis() + 4000;
        });
        HudRenderCallback.EVENT.register((graphics, delta) -> {
            long remaining = hudUntil - System.currentTimeMillis();
            if (remaining <= 0) return;
            var client = net.minecraft.client.Minecraft.getInstance();
            Component message = Component.translatable("jane.hud.aligned");
            int alpha = remaining < 1000 ? (int) (remaining * 255 / 1000) : 255;
            int x = client.getWindow().getGuiScaledWidth() - client.font.width(message) - 10;
            int y = client.getWindow().getGuiScaledHeight() - 24;
            graphics.drawString(client.font, message, x, y, (alpha << 24) | 0xFFFFFF);
        });
    }

    static FriendlyByteBuf response(LoginStatus status) {
        FriendlyByteBuf result = new FriendlyByteBuf(Unpooled.buffer(1));
        result.writeByte(status.code());
        return result;
    }

    /** The confirmation UI can authorize only the next matching login, never a persistent bypass. */
    static boolean confirmRequiredOverride(Minecraft client, PendingSyncContext context,
                                           ReconnectTarget target, SyncSelection selection) {
        if (client == null || context == null || target == null || selection == null) return false;
        try {
            if (!ServerIdentity.normalize(target.data().ip).equals(context.serverAddress())) return false;
            if (!OVERRIDE.arm(context.serverId(), context.manifest(), selection, context.results())) return false;
            try {
                ReconnectHelper.reconnect(client, target);
            } catch (RuntimeException exception) {
                OVERRIDE.clear();
                LOGGER.error("{}required override automatic reconnect failed", JaneLog.client(context.serverId()),
                        exception);
                return false;
            }
            LOGGER.warn("{}playerAction=USER_OVERRIDE_RECONNECT requiredBaseline=NOT_SATISFIED "
                    + "manifestDigest={} selectionDigest={}", JaneLog.client(context.serverId()),
                    OneShotJoinOverride.manifestDigest(context.manifest()).substring(0, 12),
                    selection.digest().substring(0, 12));
            return true;
        } catch (IllegalArgumentException exception) {
            OVERRIDE.clear();
            return false;
        }
    }

    /** The decision UI only records a choice; source lookup starts if SyncScreen is opened. */
    static boolean recordEnvironmentAction(String event, PendingSyncContext context,
                                           ClientCompatibilityReport extras) {
        if (context == null || !("USER_SELECT_SYNC".equals(event)
                || "USER_SELECT_DIRECT_JOIN".equals(event))) return false;
        int extraCount = extras == null ? 0
                : extras.explicitClientMods().size() + extras.otherExtraMods().size();
        LOGGER.info("{}playerAction={} requiredBaseline={} extraClientMods={}",
                JaneLog.client(context.serverId()), event,
                Comparison.passed(context.results()) ? "PASS" : "NOT_SATISFIED", extraCount);
        return recordAudit(event, context.serverId(), Map.of(
                "manifestDigest", OneShotJoinOverride.manifestDigest(context.manifest()),
                "requiredBaseline", Comparison.passed(context.results()) ? "PASS" : "NOT_SATISFIED",
                "extraClientMods", Integer.toString(extraCount)));
    }

    /** Persist the exact risks before arming a one-shot reconnect. No paths or extra-mod list leave this client. */
    static boolean recordDirectJoinDecision(PendingSyncContext context, ClientCompatibilityReport extras) {
        if (context == null) return false;
        boolean passed = Comparison.passed(context.results());
        if (!passed && !OneShotJoinOverride.overrideable(context.manifest(), context.results())) return false;
        if (passed && !OneShotExtraJoin.hasExtras(extras)) return false;
        String prefix = JaneLog.client(context.serverId());
        int extraCount = extras == null ? 0
                : extras.explicitClientMods().size() + extras.otherExtraMods().size();
        boolean saved = true;
        if (!passed) {
            LOGGER.warn("{}playerAction=SKIP_MOD_SYNC requiredBaseline=NOT_SATISFIED skippedRequired={} "
                    + "extraClientMods={}", prefix, context.results().stream()
                    .filter(result -> result.status() != Comparison.Status.OK).count(), extraCount);
            saved &= recordAudit("SKIP_MOD_SYNC", context.serverId(), Map.of(
                    "manifestDigest", OneShotJoinOverride.manifestDigest(context.manifest()),
                    "skippedRequired", Long.toString(context.results().stream()
                            .filter(result -> result.status() != Comparison.Status.OK).count()),
                    "extraClientMods", Integer.toString(extraCount)));
            for (Comparison.Result result : context.results()) {
                if (result.status() == Comparison.Status.OK) continue;
                String shortHash = result.required().sha512().substring(0, 12);
                LOGGER.warn("{}skipRequired modId={} requiredVersion={} comparisonStatus={} requiredHash={}",
                        prefix, result.required().modId(), result.required().version(), result.status(), shortHash);
                saved &= recordAudit("SKIPPED_REQUIRED", context.serverId(), Map.of(
                        "modId", result.required().modId(),
                        "requiredVersion", result.required().version(),
                        "comparisonStatus", result.status().name(), "requiredHash", shortHash));
            }
        } else {
            LOGGER.info("{}playerAction=DIRECT_JOIN_WITH_EXTRAS requiredBaseline=PASS "
                    + "extraClientMods={} preservedExtras={}", prefix, extraCount, extraCount);
        }
        LOGGER.info("{}playerAction=DIRECT_JOIN_CONFIRMED requiredBaseline={} extraClientMods={}",
                prefix, passed ? "PASS" : "NOT_SATISFIED", extraCount);
        saved &= recordAudit("DIRECT_JOIN_CONFIRMED", context.serverId(), Map.of(
                "manifestDigest", OneShotJoinOverride.manifestDigest(context.manifest()),
                "requiredBaseline", passed ? "PASS" : "NOT_SATISFIED",
                "extraClientMods", Integer.toString(extraCount),
                "preservedExtras", Integer.toString(extraCount)));
        return saved;
    }

    static boolean confirmDirectJoin(Minecraft client, PendingSyncContext context,
                                     ClientCompatibilityReport extras, ReconnectTarget target) {
        if (client == null || context == null || target == null) return false;
        try {
            if (!ServerIdentity.normalize(target.data().ip).equals(context.serverAddress())) return false;
            if (!Comparison.passed(context.results())) {
                if (!OneShotJoinOverride.overrideable(context.manifest(), context.results())) return false;
                SyncSelection skipped = new SyncSelection(context);
                for (Comparison.Result result : context.results())
                    if (result.status() != Comparison.Status.OK)
                        skipped.setSelected(result.required().modId(), false);
                EXTRA_JOIN.clear();
                return confirmRequiredOverride(client, context, target, skipped);
            }
            if (!EXTRA_JOIN.arm(context.serverId(), context.manifest(), extras)) return false;
            OVERRIDE.clear();
            try {
                ReconnectHelper.reconnect(client, target);
                return true;
            } catch (RuntimeException exception) {
                EXTRA_JOIN.clear();
                LOGGER.error("{}extra-only direct join reconnect failed", JaneLog.client(context.serverId()),
                        exception);
                return false;
            }
        } catch (IllegalArgumentException exception) {
            EXTRA_JOIN.clear();
            return false;
        }
    }

    private static boolean recordAudit(String event, String serverId, Map<String, String> fields) {
        try {
            java.util.HashMap<String, String> all = new java.util.HashMap<>(fields);
            all.put("serverId", serverId);
            SyncDecisionAudit.record(FabricLoader.getInstance().getGameDir(), event, all);
            return true;
        } catch (IOException | SecurityException exception) {
            LOGGER.error("{}sync decision audit could not save event={} cause={}",
                    JaneLog.client(serverId), event, exception.getClass().getSimpleName());
            return false;
        }
    }

    static void recheckPending() {
        startupChecked = false;
    }

    static void discardContext(PendingSyncContext context) {
        PENDING.clearIfContext(context);
    }
}
