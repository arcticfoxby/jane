package dev.modsbyfox.jane.fabric.client;

/** Separate, fixed declaration and independently scrolling Mod list. */
record CompatibilityReviewLayout(int declarationTop, int declarationBottom, int declarationContentHeight,
                                 int listTop, int listBottom, int buttonTop) {
    static CompatibilityReviewLayout forSize(int height, int declarationHeight) {
        int declarationTop = 40;
        int listBottom = height - 86;
        int available = Math.max(12, listBottom - declarationTop - 5 - 20);
        int declarationBottom = declarationTop + Math.min(declarationHeight, available);
        return new CompatibilityReviewLayout(declarationTop, declarationBottom, declarationHeight,
                declarationBottom + 5, listBottom, height - 76);
    }

    int declarationScrollLimit() { return Math.max(0, declarationContentHeight - (declarationBottom - declarationTop)); }
    int declarationLineY(int lineOffset, int scroll) { return declarationTop + lineOffset - scroll; }
    int listRowY(int rowOffset, int scroll) { return listTop + rowOffset - scroll; }
    int listHeight() { return listBottom - listTop; }
}
