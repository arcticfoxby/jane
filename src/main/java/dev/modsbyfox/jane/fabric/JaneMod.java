package dev.modsbyfox.jane.fabric;

import dev.modsbyfox.jane.core.ManifestCodec;
import dev.modsbyfox.jane.core.RequiredManifest;
import dev.modsbyfox.jane.core.ServerProviderOffer;
import dev.modsbyfox.jane.core.ServerProviderService;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerLoginConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerLoginNetworking;
import net.fabricmc.api.ModInitializer;
import net.minecraft.network.FriendlyByteBuf;
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
    private volatile int advertisedPort;
    private volatile String manifestError;

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (!server.isDedicatedServer()) return;
            try {
                ServerManifest.Prepared prepared = ServerManifest.prepare();
                manifest = prepared.manifest();
                ServerConfig.Provider config = ServerConfig.read(FabricLoader.getInstance().getGameDir(),
                        FabricLoader.getInstance().getConfigDir()).provider();
                if (config.enabled()) {
                    Map<String, ServerProviderService.File> files = new HashMap<>();
                    for (var entry : manifest.entries()) {
                        files.put(entry.sha512(), new ServerProviderService.File(entry, prepared.files().get(entry.sha512())));
                    }
                    provider = new ServerProviderService(FabricLoader.getInstance().getGameDir(), files,
                            config.bindPort(), Clock.systemUTC());
                    advertisedPort = config.advertisedPort();
                }
                manifestError = null;
                LOGGER.info("Jane required manifest ready: {} entries", manifest.entries().size());
            } catch (Exception exception) {
                manifest = null;
                manifestError = exception.getMessage();
                LOGGER.error("Invalid Jane server manifest: {}", manifestError, exception);
                if (provider != null) { provider.close(); provider = null; }
                throw new IllegalStateException("Jane server initialization failed closed", exception);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (provider != null) { provider.close(); provider = null; }
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
                ServerProviderOffer offer = provider == null ? null : new ServerProviderOffer(advertisedPort,
                        provider.issue(manifest));
                byte[] encoded = ManifestCodec.encode(new ManifestCodec.LoginOffer(manifest, offer));
                sender.sendPacket(MANIFEST_CHANNEL, new FriendlyByteBuf(Unpooled.wrappedBuffer(encoded)));
            } catch (Exception exception) {
                LOGGER.error("Jane login offer could not be created", exception);
                handler.disconnect(Component.translatable("jane.protocol.incompatible"));
            }
        });
    }
}
