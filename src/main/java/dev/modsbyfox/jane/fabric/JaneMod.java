package dev.modsbyfox.jane.fabric;

import dev.modsbyfox.jane.core.ManifestCodec;
import dev.modsbyfox.jane.core.RequiredManifest;
import dev.modsbyfox.jane.core.ServerProviderOffer;
import dev.modsbyfox.jane.core.ServerProviderCore;
import dev.modsbyfox.jane.core.ServerProviderService;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerLoginConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerLoginNetworking;
import net.fabricmc.api.ModInitializer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class JaneMod implements ModInitializer {
    public static final ResourceLocation MANIFEST_CHANNEL = new ResourceLocation("jane", "manifest");
    private static final Logger LOGGER = LoggerFactory.getLogger("jane");
    private volatile RequiredManifest manifest;
    private volatile ServerProviderService provider;
    private static volatile MinecraftProviderTransport minecraftProvider;
    private volatile int advertisedPort;
    private volatile String manifestError;

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (!server.isDedicatedServer()) return;
            LOGGER.info("{}startup protocol={}", JaneLog.server(), RequiredManifest.PROTOCOL);
            try {
                ServerManifest.Prepared prepared = ServerManifest.prepare();
                manifest = prepared.manifest();
                ServerConfig.Provider config = ServerConfig.read(FabricLoader.getInstance().getGameDir(),
                        FabricLoader.getInstance().getConfigDir()).provider();
                if (config.mode() == ServerConfig.ProviderMode.SEPARATE_PORT) {
                    Map<String, ServerProviderService.File> files = new HashMap<>();
                    for (var entry : manifest.entries()) {
                        files.put(entry.sha512(), new ServerProviderService.File(entry, prepared.files().get(entry.sha512())));
                    }
                    provider = new ServerProviderService(FabricLoader.getInstance().getGameDir(), files,
                            config.bindPort().orElseThrow(), Clock.systemUTC());
                    advertisedPort = config.advertisedPort().orElseThrow();
                    LOGGER.info("Jane ServerProvider: SEPARATE_PORT");
                    LOGGER.info("Jane ServerProvider listening on local TCP port {}", provider.port());
                    LOGGER.info("Jane ServerProvider advertised TCP port {}", advertisedPort);
                } else if (config.mode() == ServerConfig.ProviderMode.AUTO
                        || config.mode() == ServerConfig.ProviderMode.MINECRAFT) {
                    Map<String, ServerProviderCore.File> files = new HashMap<>();
                    for (var entry : manifest.entries()) {
                        files.put(entry.sha512(), new ServerProviderCore.File(entry, prepared.files().get(entry.sha512())));
                    }
                    minecraftProvider = new MinecraftProviderTransport(new ServerProviderCore(
                            FabricLoader.getInstance().getGameDir(), files, Clock.systemUTC()));
                    LOGGER.info("Jane ServerProvider: {} -> MINECRAFT", config.mode());
                    LOGGER.info("Jane ServerProvider uses the existing Minecraft TCP entry; no extra port required");
                } else {
                    LOGGER.info("Jane ServerProvider: DISABLED; no server-provided JAR downloads will be offered");
                }
                manifestError = null;
                LOGGER.info("Jane required manifest ready: {} entries", manifest.entries().size());
            } catch (Exception exception) {
                manifest = null;
                manifestError = exception.getMessage();
                LOGGER.error("Invalid Jane server manifest: {}", manifestError, exception);
                if (provider != null) { provider.close(); provider = null; }
                if (minecraftProvider != null) { minecraftProvider.close(); minecraftProvider = null; }
                throw new IllegalStateException("Jane server initialization failed closed", exception);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (provider != null) { provider.close(); provider = null; }
            if (minecraftProvider != null) { minecraftProvider.close(); minecraftProvider = null; }
        });
        ServerLoginNetworking.registerGlobalReceiver(MANIFEST_CHANNEL, (server, handler, understood, buf, synchronizer, sender) -> {
            if (!server.isDedicatedServer()) return;
            if (!understood) {
                handler.disconnect(Component.literal("此服务器使用「简」进行模组环境同步。请先安装简客户端后重新连接。 / Install Jane on your client to join this server."));
                return;
            }
            if (buf == null || buf.readableBytes() != 1) {
                handler.disconnect(Component.translatable("jane.protocol.incompatible"));
                return;
            }
            int status = buf.readUnsignedByte();
            if (status == 0) return;
            if (status == 1) handler.disconnect(Component.translatable("jane.sync.mismatch"));
            else handler.disconnect(Component.translatable("jane.protocol.incompatible"));
        });
        ServerLoginConnectionEvents.QUERY_START.register((handler, server, sender, synchronizer) -> {
            if (!server.isDedicatedServer()) return;
            if (manifestError != null || manifest == null) {
                handler.disconnect(Component.literal("服务器简配置无效，请联系管理员。 / Invalid Jane server configuration; contact an administrator."));
                return;
            }
            try {
                ServerProviderOffer offer = minecraftProvider != null
                        ? ServerProviderOffer.minecraft(minecraftProvider.issue(manifest))
                        : provider != null ? ServerProviderOffer.separatePort(advertisedPort, provider.issue(manifest)) : null;
                byte[] encoded = ManifestCodec.encode(new ManifestCodec.LoginOffer(manifest, offer));
                sender.sendPacket(MANIFEST_CHANNEL, new FriendlyByteBuf(Unpooled.wrappedBuffer(encoded)));
            } catch (Exception exception) {
                LOGGER.error("Jane login offer could not be created", exception);
                handler.disconnect(Component.translatable("jane.protocol.incompatible"));
            }
        });
    }

    public static void acceptMarkedTransfer(MinecraftServer server, Connection connection, Channel channel) {
        MinecraftProviderTransport current = minecraftProvider;
        if (!server.isDedicatedServer() || current == null || channel == null) {
            if (channel != null) channel.close();
            return;
        }
        current.takeover(server, connection, channel);
    }
}
