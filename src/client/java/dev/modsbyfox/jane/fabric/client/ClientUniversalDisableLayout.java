package dev.modsbyfox.jane.fabric.client;

/** Fixed buttons and a scrollable risk statement, including narrow GUI-scale windows. */
record ClientUniversalDisableLayout(int contentTop, int contentBottom, int confirmY, int cancelY,
                                    int buttonWidth, boolean readable) {
    static ClientUniversalDisableLayout forSize(int width, int height) {
        int contentTop = height <= 170 ? 30 : 44;
        int confirmY = height - 52;
        int cancelY = height - 28;
        int contentBottom = confirmY - 4;
        int buttonWidth = Math.min(250, Math.max(20, width - 32));
        return new ClientUniversalDisableLayout(contentTop, contentBottom, confirmY, cancelY,
                buttonWidth, width >= 170 && contentBottom - contentTop >= 22);
    }

    int viewHeight() { return Math.max(0, contentBottom - contentTop); }
}
