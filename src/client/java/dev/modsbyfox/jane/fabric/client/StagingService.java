package dev.modsbyfox.jane.fabric.client;

import dev.modsbyfox.jane.core.BackupTimestamp;
import dev.modsbyfox.jane.core.Comparison;
import dev.modsbyfox.jane.core.Hashing;
import dev.modsbyfox.jane.core.JaneSyncSession;
import dev.modsbyfox.jane.core.ManifestEntry;
import dev.modsbyfox.jane.core.PathSafety;
import dev.modsbyfox.jane.core.PendingStore;
import dev.modsbyfox.jane.core.ResolutionPlan;
import dev.modsbyfox.jane.core.StagedFileVerifier;
import dev.modsbyfox.jane.core.UpdatePlan;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class StagingService {
    private static final Logger LOGGER = LoggerFactory.getLogger("jane");
    record Outcome(UpdatePlan plan) { }

    private StagingService() { }

    static void resolve(JaneSyncSession session, BooleanSupplier cancelled) throws InterruptedException {
        LOGGER.info("Jane resolve started for server {}", session.context().serverId().substring(0, 12));
        ModrinthService modrinth = new ModrinthService();
        ResolutionPlan resolution = ResolutionPlan.resolve(session.context().results(), target -> {
            if (cancelled.getAsBoolean()) throw new InterruptedException("Sync cancelled");
            try {
                return modrinth.find(target);
            } catch (IOException exception) {
                LOGGER.warn("Jane source lookup failed for {}: {}", target.modId(), exception.getMessage());
                throw exception;
            }
        });
        if (cancelled.getAsBoolean()) throw new InterruptedException("Sync cancelled");
        session.publishResolution(resolution);
        for (ResolutionPlan.Item item : resolution.items()) {
            LOGGER.info("Jane resolve {} {} hash {}", item.comparison().required().modId(), item.classification(),
                    item.comparison().required().sha512().substring(0, 12));
        }
        LOGGER.info("Jane resolve complete: {} trusted downloads, {} unresolved", resolution.count(ResolutionPlan.Classification.DOWNLOADABLE),
                resolution.count(ResolutionPlan.Classification.UNRESOLVED));
    }

    static Outcome stage(JaneSyncSession session, Path gameDir, BooleanSupplier cancelled) throws IOException, InterruptedException {
        ResolutionPlan resolution = session.snapshot().resolution();
        if (resolution == null) throw new IOException("Resolution has not completed");
        LOGGER.info("Using captured Jane server context {} for staging", session.context().serverId().substring(0, 12));
        List<ResolutionPlan.Item> queue = resolution.downloadQueue();
        if (queue.isEmpty()) return new Outcome(null);
        String syncId = UUID.randomUUID().toString();
        Path staging = PathSafety.janeDirectory(gameDir, "staging", syncId);
        ModrinthService modrinth = new ModrinthService();
        List<UpdatePlan.Operation> operations = new ArrayList<>();
        Map<String, Integer> fileNames = new HashMap<>();
        Set<String> oldNames = new HashSet<>();
        for (ResolutionPlan.Item item : queue) fileNames.merge(item.source().name().toLowerCase(Locale.ROOT), 1, Integer::sum);
        for (Comparison.Result result : session.context().results()) {
            if (result.local() != null && result.local().jar() != null) {
                oldNames.add(result.local().jar().getFileName().toString().toLowerCase(Locale.ROOT));
            }
        }
        for (ResolutionPlan.Item item : queue) {
            if (cancelled.getAsBoolean()) throw new InterruptedException("Sync cancelled");
            ManifestEntry target = item.comparison().required();
            String modId = target.modId();
            String name = item.source().name();
            Path part = staging.resolve(name + ".part");
            Path staged = staging.resolve(name);
            AtomicLong received = new AtomicLong();
            try {
                session.update(modId, JaneSyncSession.RuntimeState.RESOLVING, 0);
                validateDestination(gameDir, item, fileNames, oldNames);
                Comparison.LocalMod local = item.comparison().local();
                String oldHash = local == null ? null : item.comparison().localHash();
                if (local != null && oldHash == null) {
                    oldHash = Hashing.sha512(PathSafety.existingJarInMods(gameDir, local.jar()));
                }
                session.update(modId, JaneSyncSession.RuntimeState.DOWNLOADING, 0);
                LOGGER.info("Jane download started for {} hash {}", modId, target.sha512().substring(0, 12));
                modrinth.download(item.source(), target, part, cancelled, bytes -> {
                    received.set(bytes);
                    session.update(modId, JaneSyncSession.RuntimeState.DOWNLOADING, bytes);
                });
                LOGGER.info("Jane download complete; verifying {}", modId);
                UpdatePlan.Operation operation = new UpdatePlan.Operation(modId, local == null ? UpdatePlan.Kind.ADD : UpdatePlan.Kind.REPLACE,
                        local == null ? null : local.jar().getFileName().toString(), oldHash,
                        name, target.sha512(), target.fileSize());
                StagedFileVerifier.verifyAndStage(part, staged, target, session, cancelled);
                operations.add(operation);
                LOGGER.info("Jane verify succeeded; staged READY {}", modId);
            } catch (InterruptedException exception) {
                cleanupPart(part);
                throw exception;
            } catch (IOException | RuntimeException exception) {
                cleanupPart(part);
                boolean verifying = session.snapshot().items().stream().anyMatch(state ->
                        state.item().comparison().required().modId().equals(modId)
                                && (state.state() == JaneSyncSession.RuntimeState.VERIFYING
                                || state.state() == JaneSyncSession.RuntimeState.FAILED));
                session.update(modId, JaneSyncSession.RuntimeState.FAILED, received.get());
                LOGGER.warn("Jane {} failed for {}: {}", verifying ? "verify" : "download", modId, exception.getMessage());
            }
        }
        boolean allReady = resolution.count(ResolutionPlan.Classification.UNRESOLVED) == 0
                && session.snapshot().items().stream().allMatch(item ->
                item.item().classification() == ResolutionPlan.Classification.ALREADY_PRESENT
                        || item.state() == JaneSyncSession.RuntimeState.READY);
        if (!allReady) {
            LOGGER.info("Jane preparation complete; pending install withheld ({} unresolved, {} ready, {} requested)",
                    resolution.count(ResolutionPlan.Classification.UNRESOLVED), session.snapshot().readyCount(), queue.size());
            return new Outcome(null);
        }
        if (cancelled.getAsBoolean()) throw new InterruptedException("Sync cancelled");
        String timestamp = BackupTimestamp.next(gameDir, session.context().serverId());
        UpdatePlan plan = new UpdatePlan(syncId, session.context().serverId(), timestamp, operations);
        PendingStore.create(gameDir, plan);
        LOGGER.info("Jane preparation complete; pending install ready for {}", syncId);
        return new Outcome(plan);
    }

    private static void cleanupPart(Path part) {
        try { Files.deleteIfExists(part); }
        catch (IOException exception) { LOGGER.warn("Jane could not remove incomplete download", exception); }
    }

    private static void validateDestination(Path gameDir, ResolutionPlan.Item item, Map<String, Integer> fileNames,
                                            Set<String> oldNames) throws IOException {
        String name = PathSafety.safeJarName(item.source().name());
        if (fileNames.get(name.toLowerCase(Locale.ROOT)) > 1) throw new IOException("Duplicate download file name");
        Comparison.LocalMod local = item.comparison().local();
        String oldName = null;
        if (local != null) {
            Path old = PathSafety.existingJarInMods(gameDir, local.jar());
            oldName = PathSafety.safeJarName(old.getFileName().toString());
        }
        Path destination = PathSafety.newJarInMods(gameDir, name);
        boolean replacingSameName = oldName != null && name.equalsIgnoreCase(oldName);
        if ((Files.exists(destination) || oldNames.contains(name.toLowerCase(Locale.ROOT))) && !replacingSameName) {
            throw new IOException("Download file name conflicts with a local JAR");
        }
    }
}
