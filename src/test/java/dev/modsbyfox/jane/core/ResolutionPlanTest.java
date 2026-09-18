package dev.modsbyfox.jane.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResolutionPlanTest {
    private static final String HASH = "a".repeat(128);

    private static ManifestEntry entry(int index, long size) {
        return new ManifestEntry("mod" + index, "Mod " + index, "1.0", size, HASH);
    }

    private static List<Comparison.Result> missing(int count) {
        List<ManifestEntry> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) entries.add(entry(i, 100 + i));
        return Comparison.compare(new RequiredManifest(1, entries), Map.of(), path -> HASH);
    }

    private static ResolutionPlan.Source source(ManifestEntry entry) {
        return new ResolutionPlan.Source(entry.modId() + ".jar", URI.create("https://cdn.modrinth.com/test.jar"), entry.fileSize());
    }

    @Test
    void oneUnresolvedDoesNotStopSixTrustedResults() throws Exception {
        List<String> visited = new ArrayList<>();
        ResolutionPlan plan = ResolutionPlan.resolve(missing(7), entry -> {
            visited.add(entry.modId());
            return entry.modId().equals("mod2") ? Optional.empty() : Optional.of(source(entry));
        });
        assertEquals(7, visited.size());
        assertEquals(6, plan.count(ResolutionPlan.Classification.DOWNLOADABLE));
        assertEquals(1, plan.count(ResolutionPlan.Classification.UNRESOLVED));
        assertEquals(6, plan.downloadQueue().size());
        assertFalse(plan.downloadQueue().stream().anyMatch(i -> i.comparison().required().modId().equals("mod2")));
    }

    @Test
    void lookupFailureDoesNotStopLaterFiles() throws Exception {
        ResolutionPlan plan = ResolutionPlan.resolve(missing(3), entry -> {
            if (entry.modId().equals("mod1")) throw new IOException("HTTP 404");
            return Optional.of(source(entry));
        });
        assertEquals(List.of(ResolutionPlan.Classification.DOWNLOADABLE, ResolutionPlan.Classification.UNRESOLVED,
                ResolutionPlan.Classification.DOWNLOADABLE), plan.items().stream().map(ResolutionPlan.Item::classification).toList());
    }

    @Test
    void exactPresentRequiredModIsExcludedFromDownloadQueue() throws Exception {
        ManifestEntry required = entry(0, 100);
        RequiredManifest manifest = new RequiredManifest(1, List.of(required));
        var installed = Map.of(required.modId(), new Comparison.LocalMod(required.modId(), required.version(), Path.of("mod0.jar")),
                "sodium", new Comparison.LocalMod("sodium", "1", Path.of("sodium.jar")));
        ResolutionPlan plan = ResolutionPlan.resolve(Comparison.compare(manifest, installed, path -> HASH), entry -> {
            fail("Already present file must not be resolved");
            return Optional.empty();
        });
        assertEquals(1, plan.count(ResolutionPlan.Classification.ALREADY_PRESENT));
        assertTrue(plan.downloadQueue().isEmpty());
        assertEquals(1, plan.items().size());
    }

    @Test
    void progressUsesBytesRatherThanCompletedFileCount() throws Exception {
        ManifestEntry a = entry(0, 100);
        ManifestEntry b = entry(1, 900);
        List<Comparison.Result> comparisons = Comparison.compare(new RequiredManifest(1, List.of(a, b)), Map.of(), path -> HASH);
        JaneSyncSession session = new JaneSyncSession(new PendingSyncContext("example.org", new RequiredManifest(1, List.of(a, b)), comparisons));
        session.publishResolution(ResolutionPlan.resolve(comparisons, entry -> Optional.of(source(entry))));
        assertTrue(session.startDownloads());
        assertFalse(session.startDownloads());
        session.update("mod0", JaneSyncSession.RuntimeState.READY, 100);
        session.update("mod1", JaneSyncSession.RuntimeState.DOWNLOADING, 200);
        assertEquals(300, session.snapshot().downloadedBytes());
        assertEquals(1000, session.snapshot().totalBytes());
        assertEquals(30, session.snapshot().progressPercent());
    }

    @Test
    void wrongHashMustFailBeforeReady(@TempDir Path temp) throws Exception {
        Path part = temp.resolve("file.jar.part");
        Files.writeString(part, "not the expected content");
        ManifestEntry target = new ManifestEntry("example", "Example", "1", Files.size(part), HASH);
        List<Comparison.Result> comparisons = Comparison.compare(new RequiredManifest(1, List.of(target)), Map.of(), path -> HASH);
        JaneSyncSession session = new JaneSyncSession(new PendingSyncContext("example.org", new RequiredManifest(1, List.of(target)), comparisons));
        session.publishResolution(ResolutionPlan.resolve(comparisons, entry -> Optional.of(source(entry))));
        session.startDownloads();
        session.update("example", JaneSyncSession.RuntimeState.DOWNLOADING, target.fileSize());
        assertThrows(IOException.class, () -> StagedFileVerifier.verifyAndStage(part, temp.resolve("file.jar"), target,
                session, () -> false));
        session.finish(null);
        assertEquals(JaneSyncSession.RuntimeState.FAILED, session.snapshot().items().get(0).state());
        assertFalse(session.snapshot().canInstall());
        assertFalse(Files.exists(temp.resolve("file.jar")));
    }

    @Test
    void correctHashBecomesReadyOnlyAfterVerifiedMove(@TempDir Path temp) throws Exception {
        Path part = temp.resolve("file.jar.part");
        Files.writeString(part, "trusted bytes");
        ManifestEntry target = new ManifestEntry("example", "Example", "1", Files.size(part), Hashing.sha512(part));
        RequiredManifest manifest = new RequiredManifest(1, List.of(target));
        List<Comparison.Result> comparisons = Comparison.compare(manifest, Map.of(), path -> HASH);
        JaneSyncSession session = new JaneSyncSession(new PendingSyncContext("example.org", manifest, comparisons));
        session.publishResolution(ResolutionPlan.resolve(comparisons, entry -> Optional.of(source(entry))));
        session.startDownloads();
        session.update("example", JaneSyncSession.RuntimeState.DOWNLOADING, target.fileSize());
        Path staged = temp.resolve("file.jar");
        StagedFileVerifier.verifyAndStage(part, staged, target, session, () -> false);
        assertEquals(JaneSyncSession.RuntimeState.READY, session.snapshot().items().get(0).state());
        assertTrue(Files.exists(staged));
        assertFalse(Files.exists(part));
        session.finish(null);
        assertTrue(session.snapshot().canInstall());
    }

    @Test
    void fiveReadyOneFailedOneUnresolvedCannotInstall() throws Exception {
        List<Comparison.Result> comparisons = missing(7);
        RequiredManifest manifest = new RequiredManifest(1, comparisons.stream().map(Comparison.Result::required).toList());
        JaneSyncSession session = new JaneSyncSession(new PendingSyncContext("example.org", manifest, comparisons));
        session.publishResolution(ResolutionPlan.resolve(comparisons, entry ->
                entry.modId().equals("mod6") ? Optional.empty() : Optional.of(source(entry))));
        session.startDownloads();
        for (int i = 0; i < 5; i++) session.update("mod" + i, JaneSyncSession.RuntimeState.READY, entry(i, 100 + i).fileSize());
        session.update("mod5", JaneSyncSession.RuntimeState.FAILED, 50);
        session.finish(null);
        assertEquals(5, session.snapshot().readyCount());
        assertEquals(1, session.snapshot().resolution().count(ResolutionPlan.Classification.UNRESOLVED));
        assertFalse(session.snapshot().canInstall());
        assertEquals(JaneSyncSession.RuntimeState.READY, session.snapshot().items().get(0).state());
    }
}
