package dev.modsbyfox.jane.core;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DownloadCoordinatorTest {
    private JaneSyncSession session(boolean provider, ResolutionPlan.Availability... availability) {
        long[] sizes = new long[availability.length];
        for (int index = 0; index < availability.length; index++) sizes[index] = 100L * (index + 1);
        return session(provider, sizes, availability);
    }

    private JaneSyncSession session(boolean provider, long[] sizes, ResolutionPlan.Availability... availability) {
        List<ManifestEntry> entries = new ArrayList<>();
        List<Comparison.Result> comparisons = new ArrayList<>();
        List<ResolutionPlan.Item> items = new ArrayList<>();
        for (int index = 0; index < availability.length; index++) {
            ManifestEntry entry = new ManifestEntry("mod" + index, "Mod " + index, "1", sizes[index],
                    "a".repeat(128));
            entries.add(entry);
            Comparison.Result comparison = new Comparison.Result(entry, null,
                    availability[index] == ResolutionPlan.Availability.ALREADY_PRESENT
                            ? Comparison.Status.OK : Comparison.Status.MISSING, null);
            comparisons.add(comparison);
            ResolutionPlan.Source source = availability[index] == ResolutionPlan.Availability.TRUSTED_AVAILABLE
                    ? new ResolutionPlan.Source("mod" + index + ".jar",
                    URI.create("https://cdn.modrinth.com/test.jar"), entry.fileSize()) : null;
            items.add(new ResolutionPlan.Item(comparison, availability[index], source));
        }
        RequiredManifest manifest = new RequiredManifest(RequiredManifest.PROTOCOL, entries);
        JaneSyncSession session = new JaneSyncSession(new PendingSyncContext("example.org", manifest, comparisons,
                provider ? ServerProviderOffer.minecraft("a".repeat(64)) : null));
        session.publishResolution(new ResolutionPlan(items));
        return session;
    }

    @Test void bothSourcesDisabledNeverStartsTransfer() {
        JaneSyncSession session = session(true, ResolutionPlan.Availability.TRUSTED_AVAILABLE);
        DownloadCoordinator coordinator = new DownloadCoordinator(false, false, true);
        assertEquals(DownloadCoordinator.Action.NO_SOURCE, coordinator.next(session.snapshot()));
        assertFalse(session.snapshot().running());
    }

    @Test void trustedFirstThenFailedFileFallsBackToServerOnce() {
        JaneSyncSession session = session(true, ResolutionPlan.Availability.TRUSTED_AVAILABLE,
                ResolutionPlan.Availability.TRUSTED_AVAILABLE);
        DownloadCoordinator coordinator = new DownloadCoordinator(true, true, true);
        assertEquals(DownloadCoordinator.Action.TRUSTED, coordinator.next(session.snapshot()));
        assertTrue(session.startTransfer(ResolutionPlan.TransferGroup.TRUSTED,
                ResolutionPlan.TransferRoute.TRUSTED_SOURCE));
        session.update("mod0", JaneSyncSession.RuntimeState.READY, 100);
        session.update("mod1", JaneSyncSession.RuntimeState.FAILED, 200);
        session.finishTransfer(false, null);
        assertEquals(List.of("mod1"), session.snapshot().queue(ResolutionPlan.TransferGroup.TRUSTED).stream()
                .map(item -> item.comparison().required().modId()).toList());
        assertEquals(DownloadCoordinator.Action.TRUSTED_SERVER_FALLBACK, coordinator.next(session.snapshot()));
        assertTrue(session.startTransfer(ResolutionPlan.TransferGroup.TRUSTED,
                ResolutionPlan.TransferRoute.CURRENT_SERVER));
        session.update("mod1", JaneSyncSession.RuntimeState.READY, 200);
        session.finishTransfer(false, null);
        assertEquals(DownloadCoordinator.Action.COMPLETE, coordinator.next(session.snapshot()));
        assertEquals(1, session.snapshot().items().stream().filter(item ->
                item.completedVia() == ResolutionPlan.TransferRoute.TRUSTED_SOURCE).count());
        assertEquals(1, session.snapshot().items().stream().filter(item ->
                item.completedVia() == ResolutionPlan.TransferRoute.CURRENT_SERVER).count());
    }

    @Test void trustedDisabledUsesServerRouteWithoutPublicDownload() {
        JaneSyncSession session = session(true, ResolutionPlan.Availability.TRUSTED_AVAILABLE);
        DownloadCoordinator coordinator = new DownloadCoordinator(false, true, true);
        assertEquals(DownloadCoordinator.Action.TRUSTED_SERVER_FALLBACK, coordinator.next(session.snapshot()));
        assertEquals(DownloadCoordinator.Action.INCOMPLETE, coordinator.next(session.snapshot()));
    }

    @Test void serverOnlyAlwaysRequiresSeparateConfirmationEvenWhenServerEnabled() {
        JaneSyncSession session = session(true, ResolutionPlan.Availability.TRUSTED_AVAILABLE,
                ResolutionPlan.Availability.SERVER_ONLY);
        DownloadCoordinator coordinator = new DownloadCoordinator(true, true, true);
        assertEquals(DownloadCoordinator.Action.TRUSTED, coordinator.next(session.snapshot()));
        assertTrue(session.startTransfer(ResolutionPlan.TransferGroup.TRUSTED,
                ResolutionPlan.TransferRoute.TRUSTED_SOURCE));
        session.update("mod0", JaneSyncSession.RuntimeState.FAILED, 0);
        session.finishTransfer(false, null);
        assertEquals(DownloadCoordinator.Action.TRUSTED_SERVER_FALLBACK, coordinator.next(session.snapshot()));
        assertTrue(session.startTransfer(ResolutionPlan.TransferGroup.TRUSTED,
                ResolutionPlan.TransferRoute.CURRENT_SERVER));
        session.update("mod0", JaneSyncSession.RuntimeState.FAILED, 0);
        session.finishTransfer(false, null);
        assertEquals(DownloadCoordinator.Action.SERVER_ONLY_CONFIRM, coordinator.next(session.snapshot()));
        assertEquals(DownloadCoordinator.Action.INCOMPLETE, coordinator.next(session.snapshot()));
        coordinator.confirmServerOnly();
        assertEquals(DownloadCoordinator.Action.SERVER_ONLY, coordinator.next(session.snapshot()));
        assertTrue(session.startServerOnlyConfirmed());
        session.update("mod1", JaneSyncSession.RuntimeState.READY, 200);
        session.finishTransfer(false, null);
        assertEquals(DownloadCoordinator.Action.INCOMPLETE, coordinator.next(session.snapshot()));
        assertFalse(session.snapshot().canInstall());
    }

    @Test void confirmationCannotBeInventedWithoutPrompt() {
        DownloadCoordinator coordinator = new DownloadCoordinator(true, true, true);
        assertThrows(IllegalStateException.class, coordinator::confirmServerOnly);
    }

    @Test void lookupFailureRemainsIncompleteAndCannotBecomePending() {
        JaneSyncSession session = session(true, ResolutionPlan.Availability.LOOKUP_FAILED);
        DownloadCoordinator coordinator = new DownloadCoordinator(true, true, true);
        assertEquals(DownloadCoordinator.Action.LOOKUP_FAILED, coordinator.next(session.snapshot()));
        assertFalse(session.snapshot().canInstall());
    }

    @Test void missingProviderIsReportedAndNoServerRouteRuns() {
        JaneSyncSession session = session(false, ResolutionPlan.Availability.UNRESOLVED);
        DownloadCoordinator coordinator = new DownloadCoordinator(true, true, false);
        assertEquals(DownloadCoordinator.Action.UNAVAILABLE, coordinator.next(session.snapshot()));
    }

    @Test void globalAndLocalErrorsDoNotConsumeTransferRoutes() {
        JaneSyncSession local = session(true, ResolutionPlan.Availability.LOCAL_ERROR,
                ResolutionPlan.Availability.TRUSTED_AVAILABLE);
        DownloadCoordinator blocked = new DownloadCoordinator(true, true, true);
        assertEquals(DownloadCoordinator.Action.INCOMPLETE, blocked.next(local.snapshot()));
        assertEquals(DownloadCoordinator.Action.INCOMPLETE, blocked.next(local.snapshot()));

        JaneSyncSession global = session(true, ResolutionPlan.Availability.TRUSTED_AVAILABLE);
        global.finishTransfer(false, "staging");
        assertEquals(DownloadCoordinator.Action.INCOMPLETE,
                new DownloadCoordinator(true, true, true).next(global.snapshot()));
    }

    @Test void cancellationRetainsReadyAndNewRunTouchesOnlyRemainingFile() {
        JaneSyncSession session = session(true, ResolutionPlan.Availability.TRUSTED_AVAILABLE,
                ResolutionPlan.Availability.TRUSTED_AVAILABLE);
        assertTrue(session.startTransfer(ResolutionPlan.TransferGroup.TRUSTED,
                ResolutionPlan.TransferRoute.TRUSTED_SOURCE));
        session.update("mod0", JaneSyncSession.RuntimeState.READY, 100);
        session.update("mod1", JaneSyncSession.RuntimeState.DOWNLOADING, 75);
        session.finishTransfer(true, null);
        assertEquals(JaneSyncSession.RuntimeState.READY, session.snapshot().items().get(0).state());
        assertEquals(List.of("mod1"), session.snapshot().queue(ResolutionPlan.TransferGroup.TRUSTED).stream()
                .map(item -> item.comparison().required().modId()).toList());
        assertEquals(DownloadCoordinator.Action.TRUSTED,
                new DownloadCoordinator(true, true, true).next(session.snapshot()));
    }

    @Test void overallProgressUsesSelectedBytesAndIgnoresFailedBytes() {
        JaneSyncSession session = session(true, ResolutionPlan.Availability.TRUSTED_AVAILABLE,
                ResolutionPlan.Availability.TRUSTED_AVAILABLE);
        assertTrue(session.startTransfer(ResolutionPlan.TransferGroup.TRUSTED,
                ResolutionPlan.TransferRoute.TRUSTED_SOURCE));
        session.update("mod0", JaneSyncSession.RuntimeState.READY, 100);
        session.update("mod1", JaneSyncSession.RuntimeState.DOWNLOADING, 100);
        assertEquals(300, session.snapshot().selectedTransferTotalBytes());
        assertEquals(200, session.snapshot().selectedTransferredBytes());
        assertEquals(66, session.snapshot().overallProgressPercent());
        session.update("mod1", JaneSyncSession.RuntimeState.FAILED, 200);
        assertEquals(100, session.snapshot().selectedTransferredBytes());
        assertEquals(33, session.snapshot().overallProgressPercent());
        session.finishTransfer(false, null);
        assertFalse(session.selectModIds(Set.of("mod0")));
    }

    @Test void selectedLookupFailureKeepsOverallProgressBelowOneHundredPercent() {
        JaneSyncSession session = session(true, new long[] {100, 100},
                ResolutionPlan.Availability.TRUSTED_AVAILABLE,
                ResolutionPlan.Availability.LOOKUP_FAILED);
        assertTrue(session.startTransfer(ResolutionPlan.TransferGroup.TRUSTED,
                ResolutionPlan.TransferRoute.TRUSTED_SOURCE));
        session.update("mod0", JaneSyncSession.RuntimeState.READY, 100);
        session.finishTransfer(false, null);
        assertEquals(200, session.snapshot().selectedTransferTotalBytes());
        assertEquals(100, session.snapshot().selectedTransferredBytes());
        assertEquals(50, session.snapshot().overallProgressPercent());
        assertFalse(session.snapshot().canInstall());
    }

    @Test void exactMatchedItemNeverEntersDownloadQueue() {
        JaneSyncSession session = session(true, ResolutionPlan.Availability.ALREADY_PRESENT,
                ResolutionPlan.Availability.TRUSTED_AVAILABLE);
        assertEquals(List.of("mod1"), session.snapshot().queue(ResolutionPlan.TransferGroup.TRUSTED).stream()
                .map(item -> item.comparison().required().modId()).toList());
        assertEquals(200, session.snapshot().selectedTransferTotalBytes());
    }

    @Test void noSelectedDownloadCanBeCompleteWithoutCreatingAnUpdatePlan() {
        JaneSyncSession session = session(true, ResolutionPlan.Availability.SERVER_ONLY);
        assertTrue(session.selectModIds(Set.of()));
        assertEquals(DownloadCoordinator.Action.COMPLETE,
                new DownloadCoordinator(true, true, true).next(session.snapshot()));
        assertEquals(0, session.snapshot().selectedTransferTotalBytes());
    }
}
