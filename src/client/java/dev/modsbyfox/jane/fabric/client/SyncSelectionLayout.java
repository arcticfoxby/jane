package dev.modsbyfox.jane.fabric.client;

import dev.modsbyfox.jane.core.ClientInstallCategory;
import dev.modsbyfox.jane.core.Comparison;
import dev.modsbyfox.jane.core.JaneSyncSession;
import java.util.List;
import java.util.Map;

/** Fixed declaration and action areas surrounding the clipped, scrollable file list. */
record SyncSelectionLayout(int listLeft, int listRight, int declarationTop, int declarationBottom,
                           int listTop, int listBottom, int progressTop, int mainY, int footerY,
                           int mainX, int mainWidth, boolean compact) {
    static final boolean DEFAULT_MATCHED_OPEN = false;
    static final boolean DEFAULT_SOURCES_OPEN = false;
    static final boolean DEFAULT_TRUSTED_SOURCE = true;
    static final boolean DEFAULT_SERVER_SOURCE = true;

    /** Called from screen initialization; session rejects a second lookup after reopening the screen. */
    static boolean beginResolutionOnce(JaneSyncSession session) {
        int lookups = (int) session.context().results().stream().filter(result ->
                result.status() != Comparison.Status.OK && result.status() != Comparison.Status.FILE_ERROR).count();
        return session.beginResolution(lookups);
    }
    static SyncSelectionLayout forSize(int width, int height) {
        return forSize(width, height, 40, false);
    }

    static SyncSelectionLayout forSize(int width, int height, int desiredDeclarationHeight,
                                       boolean showProgress) {
        boolean compact = height <= 190 || width < 300;
        boolean extreme = height <= 160;
        int mainWidth = Math.min(compact ? 190 : 250, width - 24);
        int mainY = height - (extreme ? 21 : compact ? 22 : 52);
        int footerY = compact ? 3 : height - 28;
        int left = Math.max(compact ? 8 : 10,
                width / 2 - Math.min(270, (width - (compact ? 24 : 28)) / 2));
        int declarationTop = extreme && showProgress ? 47 : compact ? 40 : 55;
        int listBottom = mainY - (extreme ? 2 : showProgress ? (compact ? 29 : 36) : (compact ? 7 : 13));
        int minimumListHeight = extreme && height <= 130 ? 22 : 40;
        int declarationHeight = Math.min(Math.max(16, desiredDeclarationHeight),
                Math.max(16, listBottom - declarationTop - minimumListHeight - 3));
        int declarationBottom = declarationTop + declarationHeight;
        int listTop = declarationBottom + 3;
        return new SyncSelectionLayout(left, width - left - (compact ? 8 : 10),
                declarationTop, declarationBottom, listTop, listBottom, listBottom + 2,
                mainY, footerY, (width - mainWidth) / 2, mainWidth, compact);
    }

    static List<Comparison.Result> pendingRows(List<Comparison.Result> results,
                                               Map<String, ClientInstallCategory> categories,
                                               ClientInstallCategory category) {
        return results.stream().filter(result -> result.status() != Comparison.Status.OK
                && categories.get(result.required().modId()) == category).toList();
    }

    static List<Comparison.Result> matchedRows(List<Comparison.Result> results) {
        return results.stream().filter(result -> result.status() == Comparison.Status.OK).toList();
    }
}
