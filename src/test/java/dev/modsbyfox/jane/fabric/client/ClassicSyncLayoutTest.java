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

    @Test void quickSourceButtonsFitBetweenProgressAndActionsAtScaledSizes() {
        int[][] windows = {{854, 480}, {427, 240}, {284, 160}, {214, 120},
                {1280, 720}, {640, 360}, {426, 240}, {1920, 1080}, {960, 540}};
        for (int[] window : windows) {
            ClassicSyncLayout layout = ClassicSyncLayout.forSize(window[0], window[1]);
            String size = window[0] + "x" + window[1];
            assertTrue(layout.quickRouteY() + 9 < layout.quickHeaderY(), size);
            assertTrue(layout.quickHeaderY() + 9 < layout.quickButtonsY(), size);
            assertTrue(layout.quickButtonX(0) >= 0, size);
            assertTrue(layout.quickButtonX(1) + layout.quickButtonWidth() <= window[0], size);
            assertTrue(layout.quickButtonsY() + 20 <
                    (layout.quickCancelInFooter() ? layout.footerY() : layout.mainY()), size);
        }
    }

    @Test void quickAdviceFitsAroundSourceControlsAtScaledSizes() {
        int[][] windows = {{854, 480}, {426, 240}, {284, 160}, {214, 120},
                {1280, 720}, {330, 221}, {640, 360}};
        for (int[] window : windows) {
            ClassicSyncLayout layout = ClassicSyncLayout.forSize(window[0], window[1]);
            String size = window[0] + "x" + window[1];
            for (int lines : new int[] {1, 2}) {
                int firstY = layout.quickHintY(lines);
                if (firstY < 0) {
                    assertEquals("214x120", size);
                    continue;
                }
                int lastBottom = firstY + (lines - 1) * 11 + 9;
                if (firstY < layout.quickHeaderY()) {
                    assertTrue(firstY >= layout.quickRouteY() + 10, size);
                    assertTrue(lastBottom < layout.quickHeaderY(), size);
                } else {
                    assertTrue(firstY >= layout.quickButtonsY() + 25, size);
                    assertTrue(lastBottom + 3 <=
                            (layout.quickCancelInFooter() ? layout.footerY() : layout.mainY()), size);
                }
            }
        }
    }
}
