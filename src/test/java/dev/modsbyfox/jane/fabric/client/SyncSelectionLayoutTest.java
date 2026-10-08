package dev.modsbyfox.jane.fabric.client;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SyncSelectionLayoutTest {
    @Test void listAndFixedButtonsDoNotOverlapCommonScaledWindows() {
        int[][] windows = {{854, 480}, {427, 240}, {284, 160}, {214, 120},
                {1280, 720}, {640, 360}, {426, 240}, {1920, 1080}, {960, 540}, {640, 360}};
        for (int[] window : windows) {
            SyncSelectionLayout layout = SyncSelectionLayout.forSize(window[0], window[1]);
            assertTrue(layout.listRight() > layout.listLeft());
            assertTrue(layout.listTop() < layout.listBottom());
            assertTrue(layout.listBottom() < layout.mainY());
            if (layout.compact()) {
                assertTrue(layout.footerY() + 18 < layout.listTop());
                assertTrue(layout.listTop() > 43);
            } else {
                assertTrue(layout.listTop() > 61);
                assertTrue(layout.mainY() + 20 < layout.footerY());
                assertTrue(layout.footerY() + 20 <= window[1]);
            }
            assertTrue(layout.mainY() + 20 <= window[1]);
            assertTrue(layout.mainX() >= 0);
            assertTrue(layout.mainX() + layout.mainWidth() <= window[0]);
        }
    }
}
