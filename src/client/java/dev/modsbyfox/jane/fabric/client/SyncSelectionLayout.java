package dev.modsbyfox.jane.fabric.client;

/** Fixed action area with a clipped, scrollable middle at small GUI scales. */
record SyncSelectionLayout(int listLeft, int listRight, int listTop, int listBottom,
                           int mainY, int footerY, int mainX, int mainWidth, boolean compact) {
    static SyncSelectionLayout forSize(int width, int height) {
        if (height <= 140) {
            int mainWidth = Math.min(190, width - 20);
            int left = Math.max(8, width / 2 - Math.min(270, (width - 24) / 2));
            return new SyncSelectionLayout(left, width - left - 8, 44, height - 28,
                    height - 22, 3, (width - mainWidth) / 2, mainWidth, true);
        }
        int mainY = height - 52;
        int footerY = height - 28;
        int top = Math.min(62, mainY - 26);
        int bottom = mainY - 13;
        int left = Math.max(10, width / 2 - Math.min(270, (width - 28) / 2));
        int mainWidth = Math.min(250, width - 24);
        return new SyncSelectionLayout(left, width - left - 10, top, bottom,
                mainY, footerY, (width - mainWidth) / 2, mainWidth, false);
    }
}
