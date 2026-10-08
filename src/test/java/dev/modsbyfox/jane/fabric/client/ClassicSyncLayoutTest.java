package dev.modsbyfox.jane.fabric.client;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ClassicSyncLayoutTest {
    @Test void originalPositionsRemainAtNormalScale() {
        ClassicSyncLayout layout = ClassicSyncLayout.forSize(854, 480);
        assertFalse(layout.compact());
        assertEquals(427, layout.mainY());
        assertEquals(452, layout.footerY());
        assertEquals(403, layout.selectionY());
        assertEquals(379, layout.retryY());
    }

    @Test void actionsStaySeparateAcrossCommonGuiScales() {
        int[][] windows = {{854, 480}, {427, 240}, {284, 160}, {214, 120},
                {1280, 720}, {640, 360}, {426, 240}, {1920, 1080}, {960, 540}};
        for (int[] window : windows) {
            ClassicSyncLayout layout = ClassicSyncLayout.forSize(window[0], window[1]);
            assertTrue(layout.mainX() >= 0);
            assertTrue(layout.mainX() + layout.mainWidth() <= window[0]);
            assertTrue(layout.mainY() >= 0);
            assertTrue(layout.mainY() + 20 < layout.footerY());
            assertTrue(layout.footerY() + 20 <= window[1]);
            if (layout.compact()) {
                assertTrue(layout.compactFooterX(2) + layout.footerWidth() <= window[0]);
            } else {
                assertTrue(layout.retryY() + 20 < layout.selectionY());
                assertTrue(layout.selectionY() + 20 < layout.mainY());
            }
        }
    }

    @Test void noticeNeverOverlapsRetryOrSelectionAtScaledSizes() {
        int[][] windows = {{854, 480}, {427, 240}, {330, 221}, {284, 160}, {214, 120}};
        for (int[] window : windows) {
            ClassicSyncLayout layout = ClassicSyncLayout.forSize(window[0], window[1]);
            for (boolean retry : new boolean[] {false, true}) {
                ClassicSyncLayout.Notice notice = layout.notice(114, 2, retry);
                if (notice.lines() == 0) continue;
                assertTrue(notice.firstY() + (notice.lines() - 1) * 11 + 9
                        < layout.actionTop(retry), window[0] + "x" + window[1]);
            }
        }
        assertEquals(0, ClassicSyncLayout.forSize(330, 221).notice(114, 2, true).lines());
    }
}
