package dev.modsbyfox.jane.core;

/**
 * One button press schedules each eligible route at most once. The actual transfers still use the
 * single JaneSyncSession and StagingWorkspace, which preserve verified READY files across routes.
 */
public final class DownloadCoordinator {
    public enum Action {
        TRUSTED, TRUSTED_SERVER_FALLBACK, SERVER_ONLY_CONFIRM, SERVER_ONLY,
        COMPLETE, LOOKUP_FAILED, UNAVAILABLE, INCOMPLETE, NO_SOURCE
    }

    private final boolean trustedEnabled;
    private final boolean serverEnabled;
    private final boolean providerAvailable;
    private boolean trustedAttempted;
    private boolean fallbackAttempted;
    private boolean confirmationRequested;
    private boolean serverOnlyConfirmed;
    private boolean serverOnlyAttempted;

    public DownloadCoordinator(boolean trustedEnabled, boolean serverEnabled, boolean providerAvailable) {
        this.trustedEnabled = trustedEnabled;
        this.serverEnabled = serverEnabled;
        this.providerAvailable = providerAvailable;
    }

    /** The caller starts the returned route, then calls next again when that batch has stopped. */
    public synchronized Action next(JaneSyncSession.Snapshot snapshot) {
        if (snapshot == null || snapshot.running() || snapshot.resolution() == null) return Action.INCOMPLETE;
        if (snapshot.error() != null || snapshot.localErrorCount() > 0
                || snapshot.resolution().count(ResolutionPlan.Availability.LOCAL_ERROR) > 0)
            return Action.INCOMPLETE;
        if (snapshot.canInstall()) return Action.COMPLETE;
        if (!trustedEnabled && !serverEnabled) return Action.NO_SOURCE;

        boolean trustedRemaining = snapshot.hasWaiting(ResolutionPlan.TransferGroup.TRUSTED);
        if (trustedRemaining && trustedEnabled && !trustedAttempted) {
            trustedAttempted = true;
            return Action.TRUSTED;
        }
        // A trusted exact-hash match permits this route only for the same Manifest target. The
        // provider's bytes still go through the existing size and SHA-512 staged-file verifier.
        if (trustedRemaining && serverEnabled && providerAvailable && !fallbackAttempted) {
            fallbackAttempted = true;
            return Action.TRUSTED_SERVER_FALLBACK;
        }

        if (snapshot.hasWaiting(ResolutionPlan.TransferGroup.SERVER_ONLY)
                && serverEnabled && providerAvailable && !serverOnlyAttempted) {
            if (!confirmationRequested) {
                confirmationRequested = true;
                return Action.SERVER_ONLY_CONFIRM;
            }
            if (serverOnlyConfirmed) {
                serverOnlyAttempted = true;
                return Action.SERVER_ONLY;
            }
        }
        if (snapshot.items().stream().anyMatch(item -> snapshot.selectedModIds().contains(
                item.item().comparison().required().modId())
                && item.item().availability() == ResolutionPlan.Availability.LOOKUP_FAILED))
            return Action.LOOKUP_FAILED;
        if (serverEnabled && !providerAvailable && snapshot.items().stream().anyMatch(item ->
                snapshot.selectedModIds().contains(item.item().comparison().required().modId())
                && item.item().availability() != ResolutionPlan.Availability.ALREADY_PRESENT
                && item.state() != JaneSyncSession.RuntimeState.READY))
            return Action.UNAVAILABLE;
        return Action.INCOMPLETE;
    }

    /** Must be called only after the dedicated SERVER_ONLY trust screen is accepted. */
    public synchronized void confirmServerOnly() {
        if (!confirmationRequested || serverOnlyAttempted)
            throw new IllegalStateException("SERVER_ONLY confirmation was not requested");
        serverOnlyConfirmed = true;
    }
}
