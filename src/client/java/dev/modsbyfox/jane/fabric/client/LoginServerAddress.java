package dev.modsbyfox.jane.fabric.client;

import dev.modsbyfox.jane.core.ServerIdentity;
import java.io.IOException;
import java.lang.reflect.Field;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.client.multiplayer.ServerData;

final class LoginServerAddress {
    private LoginServerAddress() { }

    static String capture(ClientHandshakePacketListenerImpl handler) throws IOException {
        try {
            // Match by type so this stays valid under Mojang and intermediary field names.
            Field serverDataField = null;
            for (Field field : ClientHandshakePacketListenerImpl.class.getDeclaredFields()) {
                if (field.getType() != ServerData.class) continue;
                if (serverDataField != null) throw new IOException("Ambiguous login server data");
                serverDataField = field;
            }
            if (serverDataField == null) throw new IOException("Login server data is unavailable");
            serverDataField.setAccessible(true);
            ServerData data = (ServerData) serverDataField.get(handler);
            if (data == null) throw new IOException("Login server data is missing");
            return ServerIdentity.normalize(data.ip);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            throw new IOException("Cannot capture the server address during login", exception);
        }
    }
}
