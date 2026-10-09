package dev.modsbyfox.jane.fabric.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CompatibilityReviewLayoutTest {
    @Test void declarationStaysFixedWhileLongModListScrolls() {
        CompatibilityReviewLayout layout = CompatibilityReviewLayout.forSize(360, 78);
        assertEquals(40, layout.declarationTop());
        assertEquals(118, layout.declarationBottom());
        assertEquals(123, layout.listRowY(0, 0));
        assertEquals(-177, layout.listRowY(0, 300));
        assertEquals(40, layout.declarationTop());
        assertEquals(118, layout.declarationBottom());
        assertTrue(layout.listHeight() > 0);
    }

    @Test void wrappedDeclarationLeavesScrollableListAndOriginalButtonsAtSmallGuiScale() {
        // Nine wrapped lines at Minecraft's nine-pixel font height, plus paragraph spacing.
        CompatibilityReviewLayout scaledWindow = CompatibilityReviewLayout.forSize(240, 9 * 9 + 6);
        assertTrue(scaledWindow.declarationBottom() + 5 <= scaledWindow.listTop());
        assertTrue(scaledWindow.listHeight() >= 22);
        assertTrue(scaledWindow.listBottom() + 10 <= scaledWindow.buttonTop());

        CompatibilityReviewLayout standardWindow = CompatibilityReviewLayout.forSize(360, 9 * 9 + 6);
        assertTrue(standardWindow.listHeight() > scaledWindow.listHeight());
        assertTrue(standardWindow.listBottom() + 10 <= standardWindow.buttonTop());

        // An unusually short window clips only the pinned statement panel, not the Mod list/buttons.
        CompatibilityReviewLayout shortWindow = CompatibilityReviewLayout.forSize(180, 9 * 9 + 6);
        assertTrue(shortWindow.declarationScrollLimit() > 0);
        assertTrue(shortWindow.listHeight() >= 20);
        assertTrue(shortWindow.declarationBottom() + 5 <= shortWindow.listTop());
        assertTrue(shortWindow.listBottom() + 10 <= shortWindow.buttonTop());
    }
}
