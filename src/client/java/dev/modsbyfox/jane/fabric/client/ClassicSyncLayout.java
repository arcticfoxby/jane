package dev.modsbyfox.jane.fabric.client;

/** V1.1.7 main-screen positions with a compact arrangement for scaled-down windows. */
record ClassicSyncLayout(boolean compact, boolean tiny, int mainX, int mainWidth,
                         int mainY, int footerY, int footerWidth) {
    record Notice(int firstY, int lines) { }
    static ClassicSyncLayout forSize(int width, int height) {
        boolean compact = height <= 220 || width < 330;
        boolean tiny = height <= 180;
        int mainWidth = Math.max(40, Math.min(220, width - 32));
        int mainY = height - (compact ? 42 : 53);
        int footerY = height - (compact ? 20 : 28);
        int footerWidth = compact ? Math.max(18, (width - 24) / 3)
                : Math.max(40, Math.min(130, (width - 50) / 2));
        return new ClassicSyncLayout(compact, tiny, (width - mainWidth) / 2,
                mainWidth, mainY, footerY, footerWidth);
    }

    int selectionY() { return mainY - 24; }
    int retryY() { return mainY - 48; }
    int compactFooterX(int slot) { return 8 + slot * (footerWidth + 4); }

    int actionTop(boolean retryVisible) {
        return compact ? mainY : retryVisible ? retryY() : selectionY();
    }

    Notice notice(int minimumY, int requestedLines, boolean retryVisible) {
        int count = Math.min(requestedLines, compact ? 1 : 2);
        if (count == 0) return new Notice(0, 0);
        int height = mainY + (compact ? 42 : 53);
        int latestBaseline = actionTop(retryVisible) - 13;
        int first = Math.max(minimumY, height - 116 - (count - 1) * 11);
        if (first + (count - 1) * 11 > latestBaseline) {
            count = 1;
            first = Math.max(minimumY, Math.min(height - 116, latestBaseline));
        }
        return first <= latestBaseline ? new Notice(first, count) : new Notice(0, 0);
    }
}
