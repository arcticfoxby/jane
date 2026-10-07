package dev.modsbyfox.jane.fabric.client;

import dev.modsbyfox.jane.core.ServerIdentity;
import java.util.Objects;
import net.minecraft.client.multiplayer.ServerData;

/** Retains the login handler's actual server entry, including its pack and LAN settings. */
record ReconnectTarget(ServerData data) {
    ReconnectTarget {
        Objects.requireNonNull(data, "data");
        ServerIdentity.normalize(data.ip);
    }
}
