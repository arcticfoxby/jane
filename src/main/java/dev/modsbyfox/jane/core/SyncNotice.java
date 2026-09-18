package dev.modsbyfox.jane.core;

/** Selects the final message from source classification and preparation state. */
public final class SyncNotice {
    public enum Kind { CONFIRM, FAILED_FILES, MANUAL_REMAINING, FAILED_AND_MANUAL,
        SERVER_REMAINING, SERVER_FAILED, INCOMPLETE }

    private SyncNotice() { }

    public static Kind select(JaneSyncSession.Snapshot snapshot, boolean pendingReady) {
        if (pendingReady) return Kind.CONFIRM;
        long server = snapshot.resolution() == null ? 0
                : snapshot.resolution().count(ResolutionPlan.Classification.SERVER_DOWNLOADABLE);
        if (server > 0 && snapshot.providerReady(ResolutionPlan.Classification.MODRINTH_DOWNLOADABLE)) {
            if (snapshot.items().stream().anyMatch(i -> i.item().classification() == ResolutionPlan.Classification.SERVER_DOWNLOADABLE
                    && i.state() == JaneSyncSession.RuntimeState.FAILED)) return Kind.SERVER_FAILED;
            if (snapshot.items().stream().anyMatch(i -> i.item().classification() == ResolutionPlan.Classification.SERVER_DOWNLOADABLE
                    && i.state() == JaneSyncSession.RuntimeState.WAITING)) return Kind.SERVER_REMAINING;
        }
        long failed = snapshot.failedCount();
        long unresolved = snapshot.resolution() == null ? 0
                : snapshot.resolution().count(ResolutionPlan.Classification.UNRESOLVED);
        if (failed > 0 && unresolved > 0) return Kind.FAILED_AND_MANUAL;
        if (failed > 0) return Kind.FAILED_FILES;
        if (unresolved > 0) return Kind.MANUAL_REMAINING;
        return Kind.INCOMPLETE;
    }
}
