package dev.modsbyfox.jane.fabric.client;

import static org.junit.jupiter.api.Assertions.*;

import dev.modsbyfox.jane.core.Comparison;
import dev.modsbyfox.jane.core.Hashing;
import dev.modsbyfox.jane.core.JaneSyncSession;
import dev.modsbyfox.jane.core.ManifestEntry;
import dev.modsbyfox.jane.core.PendingSyncContext;
import dev.modsbyfox.jane.core.RequiredManifest;
import dev.modsbyfox.jane.core.ResolutionPlan;
import dev.modsbyfox.jane.core.StagingWorkspace;
import dev.modsbyfox.jane.core.SyncNotice;
import dev.modsbyfox.jane.core.UpdatePlan;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StagingPreparationTest {
    @TempDir Path game;

    private JaneSyncSession session(Path old, ManifestEntry target) throws Exception {
        RequiredManifest manifest = new RequiredManifest(RequiredManifest.PROTOCOL, List.of(target));
        var local = new Comparison.LocalMod(target.modId(), "1.20.1-1.4.3", old);
        var results = Comparison.compare(manifest, Map.of(target.modId(), local), Hashing::sha512);
        assertEquals(Comparison.Status.VERSION_MISMATCH, results.get(0).status());
        JaneSyncSession session = new JaneSyncSession(new PendingSyncContext("example.org", manifest, results));
        session.publishResolution(ResolutionPlan.resolve(results, entry -> Optional.of(new ResolutionPlan.Source(
                "farmers-delight-refabricated-2.5.4.jar", URI.create("https://cdn.modrinth.com/test.jar"),
                entry.fileSize()))));
        assertEquals(ResolutionPlan.Availability.TRUSTED_AVAILABLE,
                session.snapshot().resolution().items().get(0).availability());
        return session;
    }

    @Test
    void farmersDelightUnicodeOldJarPreparesReplaceBeforeDownload() throws Exception {
        Path mods = Files.createDirectory(game.resolve("mods"));
        Path old = Files.writeString(mods.resolve("【农夫乐事】farmers-delight-fabric-1.4.3.jar"), "old");
        Path expected = Files.writeString(game.resolve("expected.jar"), "new");
        ManifestEntry target = new ManifestEntry("farmersdelight", "Farmer's Delight",
                "1.20.1-2.5.4+refabricated", Files.size(expected), Hashing.sha512(expected));
        JaneSyncSession session = session(old, target);
        var item = session.snapshot().items().get(0).item();
        String name = StagingService.fileName(item);
        UpdatePlan.Operation operation = StagingService.prepareOperation(game, item, name,
                Map.of(name.toLowerCase(Locale.ROOT), 1), Set.of(old.getFileName().toString().toLowerCase(Locale.ROOT)));
        assertEquals(UpdatePlan.Kind.REPLACE, operation.kind());
        assertEquals(old.getFileName().toString(), operation.oldFile());
        assertEquals(Hashing.sha512(old), operation.oldHash());
        assertEquals(name, operation.newFile());
    }

    @Test
    void unsafeLocalNameFailsDuringPrepareAndCannotRetryAnotherRoute() throws Exception {
        Path mods = Files.createDirectory(game.resolve("mods"));
        Path old = Files.writeString(mods.resolve("old%.jar"), "old");
        Path expected = Files.writeString(game.resolve("expected.jar"), "new");
        ManifestEntry target = new ManifestEntry("farmersdelight", "Farmer's Delight",
                "1.20.1-2.5.4+refabricated", Files.size(expected), Hashing.sha512(expected));
        JaneSyncSession session = session(old, target);
        StagingWorkspace workspace = StagingWorkspace.create(game);
        assertTrue(session.startTransfer(ResolutionPlan.TransferGroup.TRUSTED, ResolutionPlan.TransferRoute.TRUSTED_SOURCE));
        assertNull(StagingService.stage(session, game, workspace, ResolutionPlan.TransferGroup.TRUSTED,
                ResolutionPlan.TransferRoute.TRUSTED_SOURCE, () -> false).plan());
        session.finishTransfer(false, null);
        assertEquals(JaneSyncSession.RuntimeState.LOCAL_ERROR, session.snapshot().items().get(0).state());
        assertTrue(session.snapshot().queue(ResolutionPlan.TransferGroup.TRUSTED).isEmpty());
        assertFalse(session.snapshot().canInstall());
        assertFalse(session.startTransfer(ResolutionPlan.TransferGroup.TRUSTED, ResolutionPlan.TransferRoute.TRUSTED_SOURCE));
        assertEquals(SyncNotice.Kind.RUNTIME_LOCAL_ERROR, SyncNotice.select(session.snapshot(), false));
        assertFalse(Files.exists(workspace.directory().resolve("farmers-delight-refabricated-2.5.4.jar.part")));
    }

    @Test
    void duplicateWithoutExactStagesAddAlongsideEvenWhenTrustedFilenameCollides() throws Exception {
        Path mods = Files.createDirectory(game.resolve("mods"));
        Path oldA = Files.writeString(mods.resolve("old-a.jar"), "old A");
        Path oldB = Files.writeString(mods.resolve("old-b.jar"), "old B");
        Path expected = Files.writeString(game.resolve("expected.jar"), "required exact bytes");
        ManifestEntry target = new ManifestEntry("example", "Example", "2.0",
                Files.size(expected), Hashing.sha512(expected));
        RequiredManifest manifest = new RequiredManifest(RequiredManifest.PROTOCOL, List.of(target));
        var candidates = Map.of("example", List.of(
                new Comparison.LocalMod("example", "1.0", oldA),
                new Comparison.LocalMod("example", "1.1", oldB)));
        var compared = Comparison.compareCandidates(manifest, candidates, Hashing::sha512);
        assertEquals(Comparison.Status.MISSING, compared.get(0).status());
        JaneSyncSession session = new JaneSyncSession(new PendingSyncContext("example.org", manifest, compared));
        session.publishResolution(ResolutionPlan.resolve(compared, entry -> Optional.of(new ResolutionPlan.Source(
                "old-a.jar", URI.create("https://cdn.modrinth.com/test.jar"), entry.fileSize()))));
        var item = session.snapshot().items().get(0).item();
        assertEquals("old-a.jar", item.trustedSource().name());
        String localName = ResolutionPlan.serverSource(target).name();
        assertEquals(localName, StagingService.fileName(item));
        UpdatePlan.Operation operation = StagingService.prepareOperation(game, item, localName,
                Map.of(localName.toLowerCase(Locale.ROOT), 1), Set.of("old-a.jar", "old-b.jar"));
        assertEquals(UpdatePlan.Kind.ADD, operation.kind());
        assertNull(operation.oldFile());
        assertNull(operation.oldHash());
        assertEquals(localName, operation.newFile());

        StagingWorkspace workspace = StagingWorkspace.create(game);
        Files.copy(expected, workspace.directory().resolve(localName));
        assertTrue(session.startTransfer(ResolutionPlan.TransferGroup.TRUSTED, ResolutionPlan.TransferRoute.TRUSTED_SOURCE));
        session.update("example", JaneSyncSession.RuntimeState.READY, target.fileSize());
        workspace.add(operation);
        UpdatePlan plan = workspace.prepareIfComplete(game, session);
        assertNotNull(plan);
        assertEquals(List.of(operation), plan.operations());
        String batch = Files.readString(game.resolve("jane/pending/" + workspace.syncId() + "/update.bat"));
        assertFalse(batch.contains("move /Y \"mods\\old-a.jar\""));
        assertFalse(batch.contains("move /Y \"mods\\old-b.jar\""));
        assertFalse(batch.contains("del /F /Q \"mods\\old-a.jar\""));
        assertFalse(batch.contains("del /F /Q \"mods\\old-b.jar\""));
        assertTrue(Files.exists(oldA));
        assertTrue(Files.exists(oldB));
    }

    @Test
    void oneSameVersionWrongHashStillPreparesReplace() throws Exception {
        Path mods = Files.createDirectory(game.resolve("mods"));
        Path old = Files.writeString(mods.resolve("old.jar"), "modified local bytes");
        Path expected = Files.writeString(game.resolve("expected.jar"), "server exact bytes");
        ManifestEntry target = new ManifestEntry("example", "Example", "2.0",
                Files.size(expected), Hashing.sha512(expected));
        RequiredManifest manifest = new RequiredManifest(RequiredManifest.PROTOCOL, List.of(target));
        var compared = Comparison.compareCandidates(manifest, Map.of("example", List.of(
                new Comparison.LocalMod("example", "2.0", old))), Hashing::sha512);
        assertEquals(Comparison.Status.HASH_MISMATCH, compared.get(0).status());
        JaneSyncSession session = new JaneSyncSession(new PendingSyncContext("example.org", manifest, compared));
        session.publishResolution(ResolutionPlan.resolve(compared, entry -> Optional.of(new ResolutionPlan.Source(
                "new.jar", URI.create("https://cdn.modrinth.com/test.jar"), entry.fileSize()))));
        var item = session.snapshot().items().get(0).item();
        UpdatePlan.Operation operation = StagingService.prepareOperation(game, item, StagingService.fileName(item),
                Map.of("new.jar", 1), Set.of("old.jar"));
        assertEquals(UpdatePlan.Kind.REPLACE, operation.kind());
        assertEquals("old.jar", operation.oldFile());
        assertEquals(Hashing.sha512(old), operation.oldHash());
    }
}
