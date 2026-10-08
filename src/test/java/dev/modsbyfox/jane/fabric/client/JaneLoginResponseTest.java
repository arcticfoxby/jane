package dev.modsbyfox.jane.fabric.client;

import static org.junit.jupiter.api.Assertions.*;

import dev.modsbyfox.jane.core.LoginStatus;
import org.junit.jupiter.api.Test;

class JaneLoginResponseTest {
    @Test void responseRemainsOneStatusByteWithoutClientInventory() {
        for (LoginStatus status : LoginStatus.values()) {
            var packet = JaneClient.response(status);
            try {
                assertEquals(1, packet.readableBytes());
                assertEquals(status.code(), packet.readUnsignedByte());
                assertEquals(0, packet.readableBytes());
            } finally {
                packet.release();
            }
        }
    }
}
