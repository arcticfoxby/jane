package dev.modsbyfox.jane.fabric.client;

import static org.junit.jupiter.api.Assertions.*;

import dev.modsbyfox.jane.core.Comparison;
import dev.modsbyfox.jane.core.Hashing;
import dev.modsbyfox.jane.core.JaneSyncSession;
import dev.modsbyfox.jane.core.ManifestEntry;
import dev.modsbyfox.jane.core.PendingSyncContext;
import dev.modsbyfox.jane.core.RequiredManifest;
import dev.modsbyfox.jane.core.ResolutionPlan;
import dev.modsbyfox.jane.core.ServerProviderOffer;
import dev.modsbyfox.jane.core.StagingWorkspace;
import dev.modsbyfox.jane.core.UpdatePlan;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RouteSwitchingTest {
    @TempDir Path game;

    private record Fixture(JaneSyncSession session, StagingWorkspace workspace,
                           List<ManifestEntry> entries, List<byte[]> bytes) { }

    private Fixture fixture(int count) throws Exception {
        Files.createDirectory(game.resolve("mods"));
        List<ManifestEntry> entries = new ArrayList<>();
        List<byte[]> bytes = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            byte[] content = ("trusted-file-" + index).getBytes(StandardCharsets.UTF_8);
            Path original = game.resolve("fixture-" + index + ".jar");
            Files.write(original, content);
            entries.add(new ManifestEntry("mod" + index, "Mod " + index, "1", content.length,
                    Hashing.sha512(original)));
            bytes.add(content);
        }
        RequiredManifest manifest = new RequiredManifest(RequiredManifest.PROTOCOL, entries);
        List<Comparison.Result> compared = Comparison.compare(manifest, Map.of(), ignored -> "");
        JaneSyncSession session = new JaneSyncSession(new PendingSyncContext("example.org", manifest, compared,
                new ServerProviderOffer(25566, "a".repeat(64))));
        session.publishResolution(ResolutionPlan.resolve(compared, entry -> Optional.of(new ResolutionPlan.Source(
                entry.modId() + ".jar", URI.create("https://cdn.modrinth.com/test.jar"), entry.fileSize())),
                true, ignored -> { }));
        return new Fixture(session, StagingWorkspace.create(game), entries, bytes);
    }

    private void ready(Fixture fixture, int index) throws Exception {
        ManifestEntry entry = fixture.entries().get(index);
        ResolutionPlan.Item item = fixture.session().snapshot().items().get(index).item();
        Path staged = fixture.workspace().directory().resolve(item.trustedSource().name());
        Files.write(staged, fixture.bytes().get(index));
        fixture.session().update(entry.modId(), JaneSyncSession.RuntimeState.READY, entry.fileSize());
        fixture.workspace().add(new UpdatePlan.Operation(entry.modId(), UpdatePlan.Kind.ADD, null, null,
                item.trustedSource().name(), entry.sha512(), entry.fileSize()));
    }

    @Test void eightyTrustedCancelKeepsReadyAndWorkspaceThenServerRouteQueuesSeventyNine() throws Exception {
        Fixture fixture = fixture(80);
        JaneSyncSession session = fixture.session();
        assertTrue(session.startTransfer(ResolutionPlan.TransferGroup.TRUSTED, ResolutionPlan.TransferRoute.TRUSTED_SOURCE));
        ready(fixture, 0);
        session.update("mod1", JaneSyncSession.RuntimeState.DOWNLOADING, 5);
        Path part = fixture.workspace().directory().resolve("mod1.jar.part");
        Files.writeString(part, "partial");
        StagingService.cleanupPart(part);
        session.finishTransfer(true, null);
        assertFalse(session.snapshot().running());
        assertFalse(Files.exists(part));
        assertTrue(Files.isDirectory(fixture.workspace().directory()));
        assertEquals(1, fixture.workspace().operations().size());
        assertEquals(JaneSyncSession.RuntimeState.READY, session.snapshot().items().get(0).state());
        assertEquals(JaneSyncSession.RuntimeState.WAITING, session.snapshot().items().get(1).state());
        assertEquals(79, session.snapshot().queue(ResolutionPlan.TransferGroup.TRUSTED).size());
        assertTrue(session.startTransfer(ResolutionPlan.TransferGroup.TRUSTED, ResolutionPlan.TransferRoute.CURRENT_SERVER));
        assertEquals(79, session.snapshot().batchTotal());
        assertEquals(0, session.snapshot().progressPercent());
        assertEquals("mod0.jar", session.snapshot().items().get(0).item().trustedSource().name());
        assertEquals("mod1.jar", StagingService.fileName(session.snapshot().items().get(1).item()));
        for (int index = 1; index < 80; index++) ready(fixture, index);
        UpdatePlan update = fixture.workspace().prepareIfComplete(game, session);
        session.finishTransfer(false, null);
        assertNotNull(update);
        assertEquals(80, update.operations().size());
        assertEquals(80, update.operations().stream().map(UpdatePlan.Operation::modId).distinct().count());
        assertEquals("mod1.jar", update.operations().stream().filter(op -> op.modId().equals("mod1"))
                .findFirst().orElseThrow().newFile());
        assertEquals(ResolutionPlan.TransferRoute.TRUSTED_SOURCE, session.snapshot().items().get(0).completedVia());
        assertEquals(ResolutionPlan.TransferRoute.CURRENT_SERVER, session.snapshot().items().get(1).completedVia());
        assertTrue(session.snapshot().canInstall());
    }

    @Test void cancelledBatchMustFinishBeforeNextRouteAndRepeatedSwitchPreservesReady() throws Exception {
        Fixture fixture = fixture(2);
        JaneSyncSession session = fixture.session();
        assertTrue(session.startTransfer(ResolutionPlan.TransferGroup.TRUSTED, ResolutionPlan.TransferRoute.TRUSTED_SOURCE));
        ready(fixture, 0);
        // A cancellation token is set while the old worker/callback is still active.
        assertFalse(session.startTransfer(ResolutionPlan.TransferGroup.TRUSTED, ResolutionPlan.TransferRoute.CURRENT_SERVER));
        session.finishTransfer(true, null);
        assertTrue(session.startTransfer(ResolutionPlan.TransferGroup.TRUSTED, ResolutionPlan.TransferRoute.CURRENT_SERVER));
        session.update("mod1", JaneSyncSession.RuntimeState.DOWNLOADING, 3);
        assertFalse(session.startTransfer(ResolutionPlan.TransferGroup.TRUSTED, ResolutionPlan.TransferRoute.TRUSTED_SOURCE));
        session.finishTransfer(true, null);
        assertTrue(session.startTransfer(ResolutionPlan.TransferGroup.TRUSTED, ResolutionPlan.TransferRoute.TRUSTED_SOURCE));
        assertEquals(List.of("mod1"), session.snapshot().queue(ResolutionPlan.TransferGroup.TRUSTED).stream()
                .map(item -> item.comparison().required().modId()).toList());
        ready(fixture, 1);
        UpdatePlan update = fixture.workspace().prepareIfComplete(game, session);
        session.finishTransfer(false, null);
        assertEquals(2, update.operations().size());
        assertEquals(2, update.operations().stream().map(UpdatePlan.Operation::modId).distinct().count());
    }

    @Test void mixedPresentTrustedRoutesAndConfirmedServerOnlyProduceOnePendingPlan() throws Exception {
        Path mods = game.resolve("mods");
        Files.createDirectory(mods);
        List<ManifestEntry> entries = new ArrayList<>();
        List<byte[]> bytes = new ArrayList<>();
        Map<String, Comparison.LocalMod> installed = new HashMap<>();
        for (int index = 0; index < 94; index++) {
            byte[] content = ("mixed-" + index).getBytes(StandardCharsets.UTF_8);
            Path sample = game.resolve("sample-" + index + ".jar");
            Files.write(sample, content);
            String id = "mod" + index;
            entries.add(new ManifestEntry(id, id, "1", content.length, Hashing.sha512(sample)));
            bytes.add(content);
            if (index < 10) {
                Path local = mods.resolve(id + ".jar");
                Files.write(local, content);
                installed.put(id, new Comparison.LocalMod(id, "1", local));
            }
        }
        RequiredManifest manifest = new RequiredManifest(RequiredManifest.PROTOCOL, entries);
        List<Comparison.Result> compared = Comparison.compare(manifest, installed, Hashing::sha512);
        JaneSyncSession session = new JaneSyncSession(new PendingSyncContext("example.org", manifest, compared,
                new ServerProviderOffer(25566, "a".repeat(64))));
        session.publishResolution(ResolutionPlan.resolve(compared, entry ->
                Integer.parseInt(entry.modId().substring(3)) < 90
                        ? Optional.of(new ResolutionPlan.Source(entry.modId() + ".jar",
                        URI.create("https://cdn.modrinth.com/test.jar"), entry.fileSize()))
                        : Optional.empty(), true, ignored -> { }));
        assertEquals(10, session.snapshot().resolution().count(ResolutionPlan.Availability.ALREADY_PRESENT));
        assertEquals(80, session.snapshot().resolution().count(ResolutionPlan.Availability.TRUSTED_AVAILABLE));
        assertEquals(4, session.snapshot().resolution().count(ResolutionPlan.Availability.SERVER_ONLY));
        StagingWorkspace workspace = StagingWorkspace.create(game);
        assertTrue(session.startTransfer(ResolutionPlan.TransferGroup.TRUSTED, ResolutionPlan.TransferRoute.TRUSTED_SOURCE));
        for (int index = 10; index < 50; index++) stageMixed(session, workspace, entries.get(index), bytes.get(index));
        session.finishTransfer(true, null);
        assertEquals(40, session.snapshot().queue(ResolutionPlan.TransferGroup.TRUSTED).size());
        assertTrue(session.startTransfer(ResolutionPlan.TransferGroup.TRUSTED, ResolutionPlan.TransferRoute.CURRENT_SERVER));
        for (int index = 50; index < 90; index++) stageMixed(session, workspace, entries.get(index), bytes.get(index));
        session.finishTransfer(false, null);
        assertNull(workspace.prepareIfComplete(game, session));
        assertTrue(session.startServerOnlyConfirmed());
        for (int index = 90; index < 94; index++) stageMixed(session, workspace, entries.get(index), bytes.get(index));
        UpdatePlan plan = workspace.prepareIfComplete(game, session);
        session.finishTransfer(false, null);
        assertNotNull(plan);
        assertEquals(84, plan.operations().size());
        assertEquals(84, plan.operations().stream().map(UpdatePlan.Operation::modId).distinct().count());
        assertTrue(plan.operations().stream().noneMatch(op -> Integer.parseInt(op.modId().substring(3)) < 10));
        assertTrue(plan.operations().stream().filter(op -> op.modId().equals("mod90"))
                .allMatch(op -> op.newFile().startsWith("jane-mod90-")));
        assertEquals(workspace.syncId(), plan.syncId());
        assertTrue(session.snapshot().canInstall());
    }

    private void stageMixed(JaneSyncSession session, StagingWorkspace workspace, ManifestEntry entry, byte[] bytes)
            throws Exception {
        ResolutionPlan.Item item = session.snapshot().items().stream().filter(state ->
                state.item().comparison().required().modId().equals(entry.modId())).findFirst().orElseThrow().item();
        String name = StagingService.fileName(item);
        Files.write(workspace.directory().resolve(name), bytes);
        session.update(entry.modId(), JaneSyncSession.RuntimeState.READY, bytes.length);
        workspace.add(new UpdatePlan.Operation(entry.modId(), UpdatePlan.Kind.ADD, null, null,
                name, entry.sha512(), entry.fileSize()));
    }
}
