package dev.modsbyfox.jane.core;

/** Selects the final message from source classification and preparation state. */
public final class SyncNotice {
    public enum Kind { CONFIRM, FAILED_FILES, MANUAL_REMAINING, FAILED_AND_MANUAL, INCOMPLETE }

    private SyncNotice() { }

    public static Kind select(JaneSyncSession.Snapshot snapshot, boolean pendingReady) {
        if (pendingReady) return Kind.CONFIRM;
        long failed = snapshot.failedCount();
        long unresolved = snapshot.resolution() == null ? 0
                : snapshot.resolution().count(ResolutionPlan.Classification.UNRESOLVED);
        if (failed > 0 && unresolved > 0) return Kind.FAILED_AND_MANUAL;
        if (failed > 0) return Kind.FAILED_FILES;
        if (unresolved > 0) return Kind.MANUAL_REMAINING;
        return Kind.INCOMPLETE;
    }
}
