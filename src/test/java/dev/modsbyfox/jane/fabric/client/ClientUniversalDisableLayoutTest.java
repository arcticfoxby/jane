package dev.modsbyfox.jane.fabric.client;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ClientUniversalDisableLayoutTest {
    @Test void normalAndSmallWindowsKeepRiskTextScrollableAboveButtons() {
        for (int[] size : new int[][] {{427, 240}, {284, 160}, {170, 120}}) {
            var layout = ClientUniversalDisableLayout.forSize(size[0], size[1]);
            assertTrue(layout.readable());
            assertTrue(layout.viewHeight() >= 22);
            assertTrue(layout.contentBottom() + 4 <= layout.confirmY());
            assertTrue(layout.confirmY() + 20 + 4 <= layout.cancelY());
            assertTrue(layout.cancelY() + 20 <= size[1]);
        }
    }

    @Test void unreadableWindowCannotConfirm() {
        assertFalse(ClientUniversalDisableLayout.forSize(120, 120).readable());
        assertFalse(ClientUniversalDisableLayout.forSize(170, 100).readable());
    }
}
