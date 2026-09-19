package dev.modsbyfox.jane.fabric;

import dev.modsbyfox.jane.core.MinecraftTransferMarker;
import dev.modsbyfox.jane.core.ServerProviderCore;
import dev.modsbyfox.jane.core.ServerProviderWire;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketBundlePacker;
import net.minecraft.network.PacketBundleUnpacker;
import net.minecraft.network.PacketDecoder;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.Varint21FrameDecoder;
import net.minecraft.network.Varint21LengthFieldPrepender;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Takes ownership of only Jane-marked login connections, before vanilla authentication. */
final class MinecraftProviderTransport implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("jane");
    private final ServerProviderCore core;
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), task -> {
                Thread thread = new Thread(task, "Jane Minecraft Provider worker");
                thread.setDaemon(true);
                return thread;
            });
    private final Set<Channel> active = java.util.concurrent.ConcurrentHashMap.newKeySet();

    MinecraftProviderTransport(ServerProviderCore core) { this.core = core; }
    String issue(dev.modsbyfox.jane.core.RequiredManifest manifest) { return core.issue(manifest); }

    void takeover(MinecraftServer server, Connection connection, Channel channel) {
        if (!channel.eventLoop().inEventLoop()) {
            channel.close();
            return;
        }
        try {
            ChannelPipeline pipeline = channel.pipeline();
            // Names and types are from Minecraft 1.20.1 ServerConnectionListener and Connection.configureSerialization.
            expect(pipeline, "splitter", Varint21FrameDecoder.class);
            expect(pipeline, "decoder", PacketDecoder.class);
            expect(pipeline, "prepender", Varint21LengthFieldPrepender.class);
            expect(pipeline, "encoder", PacketEncoder.class);
            expect(pipeline, "unbundler", PacketBundleUnpacker.class);
            expect(pipeline, "bundler", PacketBundlePacker.class);
            if (pipeline.get("packet_handler") != connection) throw new IOException("Unexpected Minecraft packet handler");
            channel.config().setAutoRead(false);
            RawRequest handler = new RawRequest();
            pipeline.addLast("jane_transfer", handler);
            pipeline.remove("packet_handler");
            pipeline.remove("bundler");
            pipeline.remove("unbundler");
            pipeline.remove("decoder");
            pipeline.remove("encoder");
            pipeline.remove("prepender");
            if (pipeline.get("legacy_query") != null) pipeline.remove("legacy_query");
            if (pipeline.get("timeout") != null) pipeline.remove("timeout");
            pipeline.remove("splitter");
            server.getConnection().getConnections().remove(connection);
            active.add(channel);
            channel.closeFuture().addListener(future -> active.remove(channel));
            ByteBuf ack = Unpooled.buffer(8).writeInt(MinecraftTransferMarker.ACK_MAGIC)
                    .writeInt(MinecraftTransferMarker.TRANSPORT_VERSION);
            channel.writeAndFlush(ack).addListener(future -> {
                if (future.isSuccess()) channel.config().setAutoRead(true);
                else channel.close();
            });
            LOGGER.info("Jane took over a marked Minecraft login connection");
        } catch (Exception exception) {
            LOGGER.warn("Jane marked login takeover failed: {}", exception.getMessage());
            channel.close();
        }
    }

    private static void expect(ChannelPipeline pipeline, String name, Class<? extends ChannelHandler> type) throws IOException {
        if (!type.isInstance(pipeline.get(name))) throw new IOException("Unexpected Minecraft pipeline at " + name);
    }

    private final class RawRequest extends ByteToMessageDecoder {
        private ScheduledFuture<?> timeout;
        private boolean requested;

        @Override public void handlerAdded(ChannelHandlerContext context) throws Exception {
            super.handlerAdded(context);
            timeout = context.executor().schedule(() -> {
                if (!requested) context.close();
            }, 30, TimeUnit.SECONDS);
        }

        @Override protected void decode(ChannelHandlerContext context, ByteBuf input, List<Object> out) {
            if (requested) { context.close(); return; }
            if (input.readableBytes() < ServerProviderWire.REQUEST_BYTES) return;
            if (input.readableBytes() != ServerProviderWire.REQUEST_BYTES) { context.close(); return; }
            byte[] bytes = new byte[ServerProviderWire.REQUEST_BYTES];
            input.readBytes(bytes);
            ServerProviderWire.Request request;
            try { request = ServerProviderWire.readRequest(new DataInputStream(new ByteArrayInputStream(bytes))); }
            catch (IOException exception) {
                context.writeAndFlush(Unpooled.buffer(1).writeByte(ServerProviderWire.BAD_REQUEST))
                        .addListener(future -> context.close());
                return;
            }
            requested = true;
            timeout.cancel(false);
            context.channel().config().setAutoRead(false);
            try { workers.execute(() -> serve(context.channel(), request)); }
            catch (RejectedExecutionException exception) { context.close(); }
        }

        @Override public void channelInactive(ChannelHandlerContext context) throws Exception {
            if (timeout != null) timeout.cancel(false);
            super.channelInactive(context);
        }
    }

    private void serve(Channel channel, ServerProviderWire.Request request) {
        try {
            ServerProviderCore.Access access = core.open(request.token(), request.sha512());
            if (access.status() != ServerProviderWire.OK) {
                write(channel, Unpooled.buffer(1).writeByte(access.status()));
                return;
            }
            try (ServerProviderCore.Lease lease = access.lease()) {
                write(channel, Unpooled.buffer(9).writeByte(ServerProviderWire.OK).writeLong(lease.entry().fileSize()));
                byte[] buffer = new byte[64 * 1024];
                var stream = Channels.newInputStream(lease.channel());
                int count;
                while ((count = stream.read(buffer)) != -1 && channel.isActive()) {
                    write(channel, Unpooled.wrappedBuffer(Arrays.copyOf(buffer, count)));
                }
                LOGGER.info("Jane Minecraft Provider served {} hash {}", lease.entry().modId(),
                        lease.entry().sha512().substring(0, 12));
            }
        } catch (Exception exception) {
            LOGGER.warn("Jane Minecraft Provider transfer failed: {}", exception.getClass().getSimpleName());
        } finally { channel.close(); }
    }

    private static void write(Channel channel, ByteBuf bytes) throws IOException, InterruptedException {
        ChannelFuture result = channel.writeAndFlush(bytes);
        result.sync();
        if (!result.isSuccess()) throw new IOException("Provider channel write failed", result.cause());
    }

    @Override public void close() {
        for (Channel channel : active) channel.close();
        workers.shutdownNow();
        core.close();
    }
}
