package dev.modsbyfox.jane.fabric.client;

import static org.junit.jupiter.api.Assertions.*;

import dev.modsbyfox.jane.core.Hashing;
import dev.modsbyfox.jane.core.ManifestEntry;
import dev.modsbyfox.jane.core.MinecraftTransferMarker;
import dev.modsbyfox.jane.core.ServerProviderOffer;
import dev.modsbyfox.jane.core.ServerProviderWire;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MinecraftProviderClientTest {
    @TempDir Path temp;

    @Test void routeKeepsLogicalHandshakeAddressWhileUsingResolvedEndpoint() throws Exception {
        for (String logical : new String[]{"127.0.0.1", "example.test", "example.test:41476", "[::1]:41476"}) {
            MinecraftProviderRoute route = MinecraftProviderRoute.resolve(logical, address ->
                    Optional.of(ResolvedServerAddress.from(InetSocketAddress.createUnresolved("srv-backend.test", 25572))));
            assertEquals("srv-backend.test", route.endpoint().getHostString());
            assertEquals(25572, route.endpoint().getPort());
            assertEquals(logical.contains("41476") ? 41476 : 25565, route.handshakePort());
            assertEquals(logical.startsWith("[::1]") ? "::1" : logical.split(":")[0], route.handshakeHost());
        }
        assertThrows(java.io.IOException.class, () -> MinecraftProviderRoute.resolve("example.test", address -> Optional.empty()));
    }

    @Test void clientUsesMinecraftHandshakeThenExactProviderWire() throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        byte[] body = "Jane same-port fixture".getBytes(StandardCharsets.UTF_8);
        Path original = temp.resolve("source.jar");
        Files.write(original, body);
        ManifestEntry entry = new ManifestEntry("fixture", "Fixture", "1", body.length, Hashing.sha512(original));
        String token = "a".repeat(64);
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            CompletableFuture<Void> server = CompletableFuture.runAsync(() -> {
                try (var socket = listener.accept()) {
                    socket.setSoTimeout(3000);
                    DataInputStream input = new DataInputStream(socket.getInputStream());
                    DataInputStream handshake = new DataInputStream(new ByteArrayInputStream(frame(input)));
                    assertEquals(0, varint(handshake));
                    assertTrue(varint(handshake) > 0);
                    assertEquals("127.0.0.1", utf(handshake));
                    assertEquals(listener.getLocalPort(), handshake.readUnsignedShort());
                    assertEquals(2, varint(handshake));
                    DataInputStream hello = new DataInputStream(new ByteArrayInputStream(frame(input)));
                    assertEquals(0, varint(hello));
                    assertEquals(MinecraftTransferMarker.PROFILE_NAME, utf(hello));
                    assertTrue(hello.readBoolean());
                    assertEquals(MinecraftTransferMarker.PROFILE_ID.getMostSignificantBits(), hello.readLong());
                    assertEquals(MinecraftTransferMarker.PROFILE_ID.getLeastSignificantBits(), hello.readLong());
                    DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                    output.writeInt(MinecraftTransferMarker.ACK_MAGIC);
                    output.writeInt(MinecraftTransferMarker.TRANSPORT_VERSION);
                    output.flush();
                    ServerProviderWire.Request request = ServerProviderWire.readRequest(input);
                    assertEquals(token, request.token());
                    assertEquals(entry.sha512(), request.sha512());
                    output.writeByte(ServerProviderWire.OK);
                    output.writeLong(body.length);
                    output.write(body);
                    output.flush();
                } catch (Exception exception) { throw new RuntimeException(exception); }
            });
            Path part = temp.resolve("fixture.jar.part");
            MinecraftProviderClient.download("127.0.0.1:" + listener.getLocalPort(),
                    ServerProviderOffer.minecraft(token), entry, part, () -> false, count -> { });
            server.get(5, TimeUnit.SECONDS);
            assertArrayEquals(body, Files.readAllBytes(part));
        }
    }

    @Test void missingTakeoverAcknowledgementLeavesNoPart() throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        byte[] body = "fixture".getBytes(StandardCharsets.UTF_8);
        Path source = temp.resolve("source.jar");
        Files.write(source, body);
        ManifestEntry entry = new ManifestEntry("fixture", "Fixture", "1", body.length, Hashing.sha512(source));
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            CompletableFuture<Void> server = CompletableFuture.runAsync(() -> {
                try (var socket = listener.accept()) {
                    DataInputStream input = new DataInputStream(socket.getInputStream());
                    frame(input);
                    frame(input);
                    new DataOutputStream(socket.getOutputStream()).writeLong(0);
                } catch (Exception exception) { throw new RuntimeException(exception); }
            });
            Path part = temp.resolve("failed.part");
            assertThrows(java.io.IOException.class, () -> MinecraftProviderClient.download(
                    "127.0.0.1:" + listener.getLocalPort(), ServerProviderOffer.minecraft("a".repeat(64)),
                    entry, part, () -> false, count -> { }));
            server.get(5, TimeUnit.SECONDS);
            assertFalse(Files.exists(part));
        }
    }

    private static byte[] frame(DataInputStream input) throws Exception {
        int length = varint(input);
        assertTrue(length > 0 && length < 1024);
        byte[] payload = input.readNBytes(length);
        assertEquals(length, payload.length);
        return payload;
    }

    private static String utf(DataInputStream input) throws Exception {
        int length = varint(input);
        assertTrue(length >= 0 && length < 256);
        return new String(input.readNBytes(length), StandardCharsets.UTF_8);
    }

    private static int varint(DataInputStream input) throws Exception {
        int result = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int next = input.readUnsignedByte();
            result |= (next & 127) << shift;
            if ((next & 128) == 0) return result;
        }
        throw new IllegalArgumentException("VarInt too long");
    }
}
