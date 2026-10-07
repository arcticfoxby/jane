package dev.modsbyfox.jane.fabric.client;

/** Pure visibility state for mutually exclusive transfer controls. */
record SyncControls(boolean trusted, boolean serverRoute, boolean serverOnly,
                    boolean retryLookup, boolean cancelTransfer, boolean finish) {
    static SyncControls select(boolean running, boolean retryingLookup, boolean hasError,
                               boolean pendingReady, boolean resolutionStarted, boolean trustedRemaining,
                               boolean serverOnlyRemaining, boolean lookupFailed, boolean providerAvailable) {
        boolean idle = !running && !retryingLookup && !hasError && !pendingReady;
        return new SyncControls(idle && (!resolutionStarted || trustedRemaining),
                idle && trustedRemaining && providerAvailable,
                idle && serverOnlyRemaining && providerAvailable,
                idle && lookupFailed,
                running, pendingReady);
    }
}
