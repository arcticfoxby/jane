package dev.modsbyfox.jane.fabric.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

/** Preserves the logical handshake address while Minecraft resolves the TCP endpoint, including SRV. */
record MinecraftProviderRoute(String handshakeHost, int handshakePort, InetSocketAddress endpoint) {
    static MinecraftProviderRoute resolve(String logicalAddress,
            Function<ServerAddress, Optional<ResolvedServerAddress>> resolver) throws IOException {
        ServerAddress logical = ServerAddress.parseString(logicalAddress);
        ResolvedServerAddress resolved = resolver.apply(logical)
                .orElseThrow(() -> new IOException("Minecraft server address cannot be resolved"));
        return new MinecraftProviderRoute(logical.getHost(), logical.getPort(), resolved.asInetSocketAddress());
    }
}
