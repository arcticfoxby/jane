package dev.modsbyfox.jane.fabric.client;

import dev.modsbyfox.jane.core.Comparison;
import dev.modsbyfox.jane.core.ClientReviewStore;
import dev.modsbyfox.jane.core.ManifestCodec;
import dev.modsbyfox.jane.core.PendingSyncContext;
import dev.modsbyfox.jane.core.PendingRecovery;
import dev.modsbyfox.jane.core.RequiredManifest;
import dev.modsbyfox.jane.core.ServerIdentity;
import dev.modsbyfox.jane.fabric.JaneLog;
import dev.modsbyfox.jane.fabric.JaneMod;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.loader.api.FabricLoader;
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
    private static volatile long hudUntil;
    private static boolean startupChecked;

    @Override
    public void onInitializeClient() {
        LOGGER.info("{}startup protocol={}", JaneLog.clientStartup(), RequiredManifest.PROTOCOL);
        ClientLoginConnectionEvents.INIT.register((handler, client) -> {
            PENDING.begin(handler);
        });
        ClientLoginNetworking.registerGlobalReceiver(JaneMod.MANIFEST_CHANNEL, (client, handler, buf, listenerAdder) -> {
            if (buf.readableBytes() > RequiredManifest.MAX_PAYLOAD) {
                PENDING.clearForConnection(handler);
                return CompletableFuture.completedFuture(response(2));
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
                LOGGER.error("Jane could not capture the server address during login", exception);
                return CompletableFuture.completedFuture(response(2));
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
                    if (Comparison.passed(results)) {
                        LOGGER.info("{}required baseline PASS required={}", prefix, results.size());
                        if (serverAddress != null && !assessment.compatibility().explicitClientMods().isEmpty()) {
                            var report = assessment.compatibility();
                            String serverId = ServerIdentity.id(serverAddress);
                            LOGGER.info("{}compatibility review explicitClient={} otherExtra={} fingerprint={}",
                                    prefix, report.explicitClientMods().size(), report.otherExtraMods().size(),
                                    report.fingerprint().substring(0, 12));
                            if (ClientReviewStore.reviewRequired(FabricLoader.getInstance().getGameDir(),
                                    serverId, report.fingerprint())) {
                                return response(PENDING.replace(handler, new PendingClientAction.CompatibilityReview(
                                        serverId, report, new ReconnectTarget(serverData))) ? 1 : 2);
                            }
                        } else if (assessment.compatibility() != null) {
                            LOGGER.info("{}compatibility explicitClient=0 otherExtra={} decision=PASS",
                                    prefix, assessment.compatibility().otherExtraMods().size());
                        }
                        return response(PENDING.markPassed(handler) ? 0 : 2);
                    }
                    if (serverAddress == null) throw new IOException("Jane sync is unavailable for a local world");
                    PendingSyncContext context = new PendingSyncContext(serverAddress, manifest, results, offer.provider());
                    if (!PENDING.replace(handler, new PendingClientAction.RequiredSync(context))) return response(2);
                    LOGGER.info("Captured Jane sync context for server {}", context.serverId().substring(0, 12));
                    return response(1);
                } catch (IOException | IllegalArgumentException exception) {
                    PENDING.clearForConnection(handler);
                    LOGGER.error("Jane login manifest handling failed", exception);
                    return response(2);
                }
            });
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.level == null && client.screen instanceof DisconnectedScreen) {
                PendingClientAction action = PENDING.take();
                if (action instanceof PendingClientAction.RequiredSync sync)
                    client.setScreen(new SyncScreen(client.screen, sync.context()));
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

    static FriendlyByteBuf response(int code) {
        FriendlyByteBuf result = new FriendlyByteBuf(Unpooled.buffer(1));
        result.writeByte(code);
        return result;
    }

    static void recheckPending() {
        startupChecked = false;
    }

    static void discardContext(PendingSyncContext context) {
        PENDING.clearIfContext(context);
    }
}
