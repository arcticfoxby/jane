package dev.modsbyfox.jane.fabric;

import dev.modsbyfox.jane.core.ManifestCodec;
import dev.modsbyfox.jane.core.RequiredManifest;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerLoginConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerLoginNetworking;
import net.fabricmc.api.ModInitializer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class JaneMod implements ModInitializer {
    public static final ResourceLocation MANIFEST_CHANNEL = new ResourceLocation("jane", "manifest");
    private static final Logger LOGGER = LoggerFactory.getLogger("jane");
    private volatile RequiredManifest manifest;
    private volatile byte[] encoded;
    private volatile String manifestError;

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (!server.isDedicatedServer()) return;
            try {
                manifest = ServerManifest.build();
                encoded = ManifestCodec.encode(manifest);
                manifestError = null;
                LOGGER.info("Jane required manifest ready: {} entries", manifest.entries().size());
            } catch (Exception exception) {
                manifest = null;
                encoded = null;
                manifestError = exception.getMessage();
                LOGGER.error("Invalid Jane server manifest: {}", manifestError, exception);
            }
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
            if (manifestError != null || manifest == null || encoded == null) {
                handler.disconnect(Component.literal("服务器简配置无效，请联系管理员。 / Invalid Jane server configuration; contact an administrator."));
                return;
            }
            sender.sendPacket(MANIFEST_CHANNEL, new FriendlyByteBuf(Unpooled.wrappedBuffer(encoded)));
        });
    }
}
