package dev.modsbyfox.jane.fabric.client;

import dev.modsbyfox.jane.core.ManifestEntry;
import dev.modsbyfox.jane.core.MinecraftTransferMarker;
import dev.modsbyfox.jane.core.ServerProviderClient;
import dev.modsbyfox.jane.core.ServerProviderOffer;
import dev.modsbyfox.jane.core.ServerProviderTransport;
import io.netty.buffer.Unpooled;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A fresh Minecraft-entry connection per requested file; no extra Provider port. */
final class MinecraftProviderClient {
    private static final Logger LOGGER = LoggerFactory.getLogger("jane");
    private MinecraftProviderClient() { }

    static void download(String logicalAddress, ServerProviderOffer offer, ManifestEntry entry, Path part,
                         BooleanSupplier cancelled, LongConsumer progress) throws IOException {
        if (offer.transport() != ServerProviderTransport.MINECRAFT) throw new IOException("Incorrect Provider transport");
        if (Files.exists(part)) throw new IOException("Provider temporary file already exists");
        MinecraftProviderRoute route = MinecraftProviderRoute.resolve(logicalAddress,
                ServerNameResolver.DEFAULT::resolveAddress);
        Socket socket = new Socket();
        AtomicBoolean finished = new AtomicBoolean();
        Thread cancelMonitor = new Thread(() -> {
            while (!finished.get()) {
                if (cancelled.getAsBoolean()) {
                    try { socket.close(); } catch (IOException ignored) { }
                    return;
                }
                try { Thread.sleep(100); }
                catch (InterruptedException exception) { return; }
            }
        }, "Jane Minecraft Provider cancel");
        cancelMonitor.setDaemon(true);
        cancelMonitor.start();
        boolean acknowledged = false;
        try (socket) {
            if (cancelled.getAsBoolean()) throw new IOException("Sync cancelled");
            socket.connect(route.endpoint(), 12_000);
            socket.setSoTimeout(30_000);
            OutputStream out = socket.getOutputStream();
            writePacket(out, ConnectionProtocol.HANDSHAKING,
                    new ClientIntentionPacket(route.handshakeHost(), route.handshakePort(), ConnectionProtocol.LOGIN));
            writePacket(out, ConnectionProtocol.LOGIN,
                    new ServerboundHelloPacket(MinecraftTransferMarker.PROFILE_NAME,
                            Optional.of(MinecraftTransferMarker.PROFILE_ID)));
            DataInputStream in = new DataInputStream(socket.getInputStream());
            if (in.readInt() != MinecraftTransferMarker.ACK_MAGIC
                    || in.readInt() != MinecraftTransferMarker.TRANSPORT_VERSION)
                throw new IOException("Jane Minecraft transfer takeover was not acknowledged");
            acknowledged = true;
            if (cancelled.getAsBoolean()) throw new IOException("Sync cancelled");
            ServerProviderClient.transfer(in, out, offer, entry, part, cancelled, progress);
        } catch (IOException | RuntimeException exception) {
            Files.deleteIfExists(part);
            if (!acknowledged && !cancelled.getAsBoolean()) LOGGER.warn(
                    "Jane Minecraft transfer entry could not be reached. Minecraft-aware proxies may require SEPARATE_PORT or proxy-specific support.");
            throw exception;
        } finally {
            finished.set(true);
            cancelMonitor.interrupt();
        }
    }

    /** Uses the mapped 1.20.1 packet serializers and IDs instead of embedding packet layouts or IDs. */
    private static void writePacket(OutputStream output, ConnectionProtocol protocol, Packet<?> packet) throws IOException {
        FriendlyByteBuf payload = new FriendlyByteBuf(Unpooled.buffer());
        FriendlyByteBuf frame = new FriendlyByteBuf(Unpooled.buffer());
        try {
            int id = protocol.getPacketId(PacketFlow.SERVERBOUND, packet);
            if (id < 0) throw new IOException("Minecraft transfer packet is not registered");
            payload.writeVarInt(id);
            packet.write(payload);
            frame.writeVarInt(payload.readableBytes());
            frame.writeBytes(payload);
            byte[] bytes = new byte[frame.readableBytes()];
            frame.readBytes(bytes);
            output.write(bytes);
            output.flush();
        } finally {
            frame.release();
            payload.release();
        }
    }
}
