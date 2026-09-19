package dev.modsbyfox.jane.fabric.client;

import dev.modsbyfox.jane.core.Comparison;
import dev.modsbyfox.jane.core.HashCache;
import dev.modsbyfox.jane.core.ManifestCodec;
import dev.modsbyfox.jane.core.PendingSyncContext;
import dev.modsbyfox.jane.core.PendingSyncContexts;
import dev.modsbyfox.jane.core.RequiredManifest;
import dev.modsbyfox.jane.core.PendingRecovery;
import dev.modsbyfox.jane.fabric.JaneMod;
import dev.modsbyfox.jane.fabric.ModOrigins;
import dev.modsbyfox.jane.fabric.JaneLog;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
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
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.FriendlyByteBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class JaneClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("jane");
    private static final PendingSyncContexts PENDING = new PendingSyncContexts();
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
            try {
                serverAddress = client.isLocalServer() || client.hasSingleplayerServer()
                        ? null : LoginServerAddress.capture(handler);
            } catch (IOException exception) {
                PENDING.clearForConnection(handler);
                LOGGER.error("Jane could not capture the server address during login", exception);
                return CompletableFuture.completedFuture(response(2));
            }
            return CompletableFuture.supplyAsync(() -> {
                try {
                    ManifestCodec.LoginOffer offer = ManifestCodec.decodeOffer(payload);
                    RequiredManifest manifest = offer.manifest();
                    List<Comparison.Result> results = compare(manifest);
                    if (Comparison.passed(results)) {
                        return response(PENDING.markPassed(handler) ? 0 : 2);
                    }
                    if (serverAddress == null) throw new IOException("Jane sync is unavailable for a local world");
                    PendingSyncContext context = new PendingSyncContext(serverAddress, manifest, results, offer.provider());
                    if (!PENDING.replace(handler, context)) return response(2);
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
                PendingSyncContext context = PENDING.take();
                if (context != null) client.setScreen(new SyncScreen(client.screen, context));
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

    private static List<Comparison.Result> compare(RequiredManifest manifest) {
        FabricLoader loader = FabricLoader.getInstance();
        Path gameDir = loader.getGameDir();
        Map<String, Comparison.LocalMod> local = new HashMap<>();
        for (var required : manifest.entries()) {
            ModContainer mod = loader.getModContainer(required.modId()).orElse(null);
            if (mod == null) continue;
            Path jar;
            try {
                jar = ModOrigins.directJar(mod, gameDir);
            } catch (IOException exception) {
                jar = null;
            }
            local.put(required.modId(), new Comparison.LocalMod(required.modId(),
                    mod.getMetadata().getVersion().getFriendlyString(), jar));
        }
        HashCache cache;
        try {
            cache = new HashCache(gameDir);
        } catch (IOException exception) {
            return Comparison.compare(manifest, local, path -> {
                if (path == null) throw new IOException("Unsafe local mod origin");
                return dev.modsbyfox.jane.core.Hashing.sha512(path);
            });
        }
        return Comparison.compare(manifest, local, path -> {
            if (path == null) throw new IOException("Unsafe local mod origin");
            return cache.hash(path);
        });
    }

    private static FriendlyByteBuf response(int code) {
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
