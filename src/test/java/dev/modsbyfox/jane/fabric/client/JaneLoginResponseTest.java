package dev.modsbyfox.jane.fabric.client;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class JaneLoginResponseTest {
    @Test void responseRemainsOneStatusByteWithoutClientInventory() {
        for (int status : new int[] { 0, 1, 2 }) {
            var packet = JaneClient.response(status);
            try {
                assertEquals(1, packet.readableBytes());
                assertEquals(status, packet.readUnsignedByte());
                assertEquals(0, packet.readableBytes());
            } finally {
                packet.release();
            }
        }
    }
}
