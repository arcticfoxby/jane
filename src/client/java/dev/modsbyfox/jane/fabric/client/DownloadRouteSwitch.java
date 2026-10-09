package dev.modsbyfox.jane.fabric.client;

import dev.modsbyfox.jane.core.JaneSyncSession;
import dev.modsbyfox.jane.core.ResolutionPlan;

/** A requested route becomes effective only after the active transfer worker has stopped. */
final class DownloadRouteSwitch {
    private ResolutionPlan.TransferRoute requested;

    static boolean available(JaneSyncSession.Snapshot snapshot, ResolutionPlan.TransferRoute route,
                             boolean providerAvailable) {
        if (snapshot == null || snapshot.resolution() == null || snapshot.error() != null
                || snapshot.localErrorCount() > 0 || route == null
                || route == ResolutionPlan.TransferRoute.CURRENT_SERVER && !providerAvailable) return false;
        return snapshot.items().stream().anyMatch(item ->
                snapshot.selectedModIds().contains(item.item().comparison().required().modId())
                        && item.state() != JaneSyncSession.RuntimeState.READY
                        && (item.item().availability() == ResolutionPlan.Availability.TRUSTED_AVAILABLE
                        || route == ResolutionPlan.TransferRoute.CURRENT_SERVER
                        && item.item().availability() == ResolutionPlan.Availability.SERVER_ONLY));
    }

    synchronized boolean request(JaneSyncSession.Snapshot snapshot, ResolutionPlan.TransferRoute route,
                                 boolean providerAvailable, boolean transferAlreadyCancelling) {
        if (requested != null || snapshot == null || !snapshot.running()
                || transferAlreadyCancelling || snapshot.activeRoute() == route
                || !available(snapshot, route, providerAvailable)) return false;
        requested = route;
        return true;
    }

    synchronized boolean pending() { return requested != null; }
    synchronized ResolutionPlan.TransferRoute requested() { return requested; }

    /** The caller invokes this only in the completed worker's client-thread callback. */
    synchronized ResolutionPlan.TransferRoute takeAfterWorkerStops(JaneSyncSession.Snapshot snapshot) {
        if (requested == null || snapshot.running()) return null;
        ResolutionPlan.TransferRoute route = requested;
        requested = null;
        return route;
    }

    synchronized void clear() { requested = null; }
}
