package dev.modsbyfox.jane.core;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SelectiveStagingTest {
    @TempDir Path game;

    private record Fixture(RequiredManifest manifest, List<Comparison.Result> results,
                           List<byte[]> contents) { }

    private Fixture missing(int count) throws Exception {
        Files.createDirectories(game.resolve("mods"));
        List<ManifestEntry> entries = new ArrayList<>();
        List<byte[]> contents = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            byte[] bytes = ("trusted file " + i).getBytes(StandardCharsets.UTF_8);
            Path source = Files.write(game.resolve("source-" + i + ".jar"), bytes);
            entries.add(new ManifestEntry("mod" + i, "Mod " + i, "1", bytes.length, Hashing.sha512(source)));
            contents.add(bytes);
        }
        RequiredManifest manifest = new RequiredManifest(RequiredManifest.PROTOCOL, entries);
        return new Fixture(manifest, Comparison.compare(manifest, Map.of(), path -> ""), contents);
    }

    private JaneSyncSession session(Fixture fixture, boolean lastUnresolved) throws Exception {
        JaneSyncSession session = new JaneSyncSession(new PendingSyncContext(
                "example.org", fixture.manifest(), fixture.results()));
        session.publishResolution(ResolutionPlan.resolve(fixture.results(), entry ->
                lastUnresolved && entry.modId().equals("mod" + (fixture.contents().size() - 1))
                        ? Optional.empty()
                        : Optional.of(new ResolutionPlan.Source(entry.modId() + ".jar",
                                URI.create("https://cdn.modrinth.com/test.jar"), entry.fileSize()))));
        return session;
    }

    @Test void onlySelectedUnmatchedFilesEnterQueueAndPendingPlan() throws Exception {
        Fixture fixture = missing(3);
        JaneSyncSession session = session(fixture, true);
        assertTrue(session.selectModIds(Set.of("mod0", "mod1")));
        assertEquals(List.of("mod0", "mod1"), session.snapshot().queue(ResolutionPlan.TransferGroup.TRUSTED)
                .stream().map(item -> item.comparison().required().modId()).toList());
        assertTrue(session.startTransfer(ResolutionPlan.TransferGroup.TRUSTED,
                ResolutionPlan.TransferRoute.TRUSTED_SOURCE));
        StagingWorkspace workspace = StagingWorkspace.create(game);
        for (int i = 0; i < 2; i++) {
            ManifestEntry entry = fixture.manifest().entries().get(i);
            String name = entry.modId() + ".jar";
            Files.write(workspace.directory().resolve(name), fixture.contents().get(i));
            session.update(entry.modId(), JaneSyncSession.RuntimeState.READY, entry.fileSize());
            workspace.add(new UpdatePlan.Operation(entry.modId(), UpdatePlan.Kind.ADD, null, null,
                    name, entry.sha512(), entry.fileSize()));
        }
        session.finishTransfer(false, null);
        assertTrue(session.snapshot().canInstall());
        UpdatePlan pending = workspace.prepareIfComplete(game, session, () -> false,
                session.snapshot().selectedModIds());
        assertNotNull(pending);
        assertEquals(Set.of("mod0", "mod1"), pending.operations().stream()
                .map(UpdatePlan.Operation::modId).collect(java.util.stream.Collectors.toSet()));
        assertFalse(Files.exists(game.resolve("jane/staging/" + workspace.syncId() + "/mod2.jar")));
    }

    @Test void selectedUnresolvedItemCannotBecomePending() throws Exception {
        Fixture fixture = missing(2);
        JaneSyncSession session = session(fixture, true);
        assertFalse(session.snapshot().canInstall());
        StagingWorkspace workspace = StagingWorkspace.create(game);
        assertNull(workspace.prepareIfComplete(game, session, () -> false,
                session.snapshot().selectedModIds()));
    }

    @Test void selectingNoFilesCreatesNeitherTransferNorPendingUpdate() throws Exception {
        Fixture fixture = missing(2);
        JaneSyncSession session = session(fixture, false);
        assertTrue(session.selectModIds(Set.of()));
        assertTrue(session.snapshot().queue(ResolutionPlan.TransferGroup.TRUSTED).isEmpty());
        assertFalse(session.startTransfer(ResolutionPlan.TransferGroup.TRUSTED,
                ResolutionPlan.TransferRoute.TRUSTED_SOURCE));
        StagingWorkspace workspace = StagingWorkspace.create(game);
        assertNull(workspace.prepareIfComplete(game, session, () -> false,
                session.snapshot().selectedModIds()));
        assertFalse(Files.exists(game.resolve("jane/pending/" + workspace.syncId())));
    }

    @Test void unselectedLocalFileErrorStillBlocksFinalInstall() throws Exception {
        Fixture fixture = missing(2);
        Comparison.Result broken = new Comparison.Result(fixture.manifest().entries().get(1), null,
                Comparison.Status.FILE_ERROR, null,
                new Comparison.LocalIssue(Comparison.ProblemKind.HASH_FAILED, List.of("broken.jar")));
        List<Comparison.Result> results = List.of(fixture.results().get(0), broken);
        JaneSyncSession session = new JaneSyncSession(new PendingSyncContext("example.org", fixture.manifest(), results));
        session.publishResolution(ResolutionPlan.resolve(results, entry -> Optional.of(new ResolutionPlan.Source(
                entry.modId() + ".jar", URI.create("https://cdn.modrinth.com/test.jar"), entry.fileSize()))));
        assertTrue(session.selectModIds(Set.of("mod0")));
        StagingWorkspace workspace = StagingWorkspace.create(game);
        ManifestEntry safe = fixture.manifest().entries().get(0);
        assertTrue(session.startTransfer(ResolutionPlan.TransferGroup.TRUSTED,
                ResolutionPlan.TransferRoute.TRUSTED_SOURCE));
        Files.write(workspace.directory().resolve("mod0.jar"), fixture.contents().get(0));
        session.update("mod0", JaneSyncSession.RuntimeState.READY, safe.fileSize());
        workspace.add(new UpdatePlan.Operation("mod0", UpdatePlan.Kind.ADD, null, null,
                "mod0.jar", safe.sha512(), safe.fileSize()));
        session.finishTransfer(false, null);
        assertFalse(session.snapshot().canInstall());
        assertNull(workspace.prepareIfComplete(game, session, () -> false,
                session.snapshot().selectedModIds()));
    }

    @Test void transferLocksSelectionUntilNextSyncSession() throws Exception {
        Fixture fixture = missing(2);
        JaneSyncSession session = session(fixture, false);
        assertTrue(session.selectModIds(Set.of("mod0")));
        assertTrue(session.startTransfer(ResolutionPlan.TransferGroup.TRUSTED,
                ResolutionPlan.TransferRoute.TRUSTED_SOURCE));
        assertFalse(session.selectModIds(Set.of("mod1")));
        session.finishTransfer(true, null);
        assertFalse(session.selectModIds(Set.of("mod1")));
        assertEquals(Set.of("mod0"), session.snapshot().selectedModIds());
    }
}
