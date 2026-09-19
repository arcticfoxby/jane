package dev.modsbyfox.jane.fabric.client;

import dev.modsbyfox.jane.core.Comparison;
import dev.modsbyfox.jane.core.Hashing;
import dev.modsbyfox.jane.core.JaneSyncSession;
import dev.modsbyfox.jane.core.ManifestEntry;
import dev.modsbyfox.jane.core.PathSafety;
import dev.modsbyfox.jane.core.ResolutionPlan;
import dev.modsbyfox.jane.core.StagedFileVerifier;
import dev.modsbyfox.jane.core.StagingWorkspace;
import dev.modsbyfox.jane.core.ServerProviderClient;
import dev.modsbyfox.jane.core.ServerProviderTransport;
import dev.modsbyfox.jane.core.UpdatePlan;
import dev.modsbyfox.jane.fabric.JaneLog;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class StagingService {
    private static final Logger LOGGER = LoggerFactory.getLogger("jane");
    record Outcome(UpdatePlan plan) { }

    private StagingService() { }

    static void resolve(JaneSyncSession session, BooleanSupplier cancelled) throws InterruptedException {
        String prefix = JaneLog.client(session.context().serverId());
        LOGGER.info("{}resolution started required={}", prefix, session.context().results().size());
        ModrinthService modrinth = new ModrinthService();
        ResolutionPlan resolution = ResolutionPlan.resolve(session.context().results(), target -> {
            if (cancelled.getAsBoolean()) throw new InterruptedException("Sync cancelled");
            try {
                return modrinth.find(target);
            } catch (IOException exception) {
                LOGGER.warn("{}source lookup failed modId={}", prefix, target.modId(), exception);
                throw exception;
            }
        }, session.context().provider() != null, session::resolutionProgress);
        if (cancelled.getAsBoolean()) throw new InterruptedException("Sync cancelled");
        session.publishResolution(resolution);
        for (ResolutionPlan.Item item : resolution.items()) {
            LOGGER.info("{}resolve modId={} availability={} hash={}", prefix, item.comparison().required().modId(), item.availability(),
                    item.comparison().required().sha512().substring(0, 12));
        }
        LOGGER.info("{}resolution complete trusted={} serverOnly={} lookupFailed={} unresolved={} alreadyPresent={}", prefix,
                resolution.count(ResolutionPlan.Availability.TRUSTED_AVAILABLE),
                resolution.count(ResolutionPlan.Availability.SERVER_ONLY),
                resolution.count(ResolutionPlan.Availability.LOOKUP_FAILED),
                resolution.count(ResolutionPlan.Availability.UNRESOLVED),
                resolution.count(ResolutionPlan.Availability.ALREADY_PRESENT));
        if (session.context().provider() == null
                && resolution.count(ResolutionPlan.Availability.UNRESOLVED) > 0) {
            LOGGER.info("{}ServerProvider unavailable; unresolved={}", prefix,
                    resolution.count(ResolutionPlan.Availability.UNRESOLVED));
        }
    }

    static void retryLookupFailures(JaneSyncSession session, BooleanSupplier cancelled) throws InterruptedException {
        ResolutionPlan old = session.snapshot().resolution();
        if (old == null) throw new IllegalStateException("Resolution not available");
        ModrinthService modrinth = new ModrinthService();
        ResolutionPlan refreshed = old.retryLookupFailures(target -> {
            if (cancelled.getAsBoolean()) throw new InterruptedException("Sync cancelled");
            try { return modrinth.find(target); }
            catch (IOException exception) {
                LOGGER.warn("{}source lookup retry failed modId={}", JaneLog.client(session.context().serverId()),
                        target.modId(), exception);
                throw exception;
            }
        }, session.context().provider() != null);
        if (cancelled.getAsBoolean()) throw new InterruptedException("Sync cancelled");
        session.publishRetryResolution(refreshed);
        LOGGER.info("{}lookup retry complete failedRemaining={}", JaneLog.client(session.context().serverId()),
                refreshed.count(ResolutionPlan.Availability.LOOKUP_FAILED));
    }

    static Outcome stage(JaneSyncSession session, Path gameDir, StagingWorkspace workspace,
                         ResolutionPlan.TransferGroup group, ResolutionPlan.TransferRoute route,
                         BooleanSupplier cancelled) throws IOException, InterruptedException {
        ResolutionPlan resolution = session.snapshot().resolution();
        if (resolution == null) throw new IOException("Resolution has not completed");
        if (!session.snapshot().running() || session.snapshot().activeGroup() != group
                || session.snapshot().activeRoute() != route
                || (group == ResolutionPlan.TransferGroup.SERVER_ONLY
                    && route != ResolutionPlan.TransferRoute.CURRENT_SERVER)
                || (route == ResolutionPlan.TransferRoute.CURRENT_SERVER && session.context().provider() == null))
            throw new IOException("Transfer route is not active for this group");
        String prefix = JaneLog.sync(workspace.syncId());
        LOGGER.info("{}route selected group={} route={}", prefix, group, route);
        List<ResolutionPlan.Item> queue = session.snapshot().queue(group);
        if (queue.isEmpty()) return new Outcome(null);
        Path staging = workspace.directory();
        ModrinthService modrinth = new ModrinthService();
        Map<String, Integer> fileNames = new HashMap<>();
        Set<String> oldNames = new HashSet<>();
        for (ResolutionPlan.Item item : resolution.items()) {
            if (item.availability() == ResolutionPlan.Availability.TRUSTED_AVAILABLE
                    || item.availability() == ResolutionPlan.Availability.SERVER_ONLY)
                fileNames.merge(fileName(item).toLowerCase(Locale.ROOT), 1, Integer::sum);
        }
        for (Comparison.Result result : session.context().results()) {
            if (result.local() != null && result.local().jar() != null) {
                oldNames.add(result.local().jar().getFileName().toString().toLowerCase(Locale.ROOT));
            }
        }
        for (ResolutionPlan.Item item : queue) {
            if (cancelled.getAsBoolean()) throw new InterruptedException("Sync cancelled");
            ManifestEntry target = item.comparison().required();
            String modId = target.modId();
            String name = fileName(item);
            Path part = staging.resolve(name + ".part");
            Path staged = staging.resolve(name);
            AtomicLong received = new AtomicLong();
            try {
                session.update(modId, JaneSyncSession.RuntimeState.RESOLVING, 0);
                validateDestination(gameDir, item, name, fileNames, oldNames);
                Comparison.LocalMod local = item.comparison().local();
                String oldHash = local == null ? null : item.comparison().localHash();
                if (local != null && oldHash == null) {
                    oldHash = Hashing.sha512(PathSafety.existingJarInMods(gameDir, local.jar()));
                }
                session.update(modId, JaneSyncSession.RuntimeState.DOWNLOADING, 0);
                long started = System.nanoTime();
                LOGGER.info("{}download started modId={} route={} hash={} bytes={}", prefix,
                        modId, route, target.sha512().substring(0, 12), target.fileSize());
                java.util.function.LongConsumer progress = bytes -> {
                    received.set(bytes);
                    session.update(modId, JaneSyncSession.RuntimeState.DOWNLOADING, bytes);
                };
                if (route == ResolutionPlan.TransferRoute.TRUSTED_SOURCE) {
                    modrinth.download(item.trustedSource(), target, part, cancelled, progress);
                } else {
                    if (session.context().provider().transport() == ServerProviderTransport.MINECRAFT)
                        MinecraftProviderClient.download(session.context().serverAddress(), session.context().provider(),
                                target, part, cancelled, progress);
                    else ServerProviderClient.download(session.context().serverAddress(), session.context().provider(),
                            target, part, cancelled, progress);
                }
                long durationMs = Math.max(1, (System.nanoTime() - started) / 1_000_000);
                LOGGER.info("{}download complete modId={} route={} bytes={} durationMs={} avgMiBps={}", prefix,
                        modId, route, received.get(), durationMs,
                        String.format(Locale.ROOT, "%.2f", received.get() * 1000.0 / durationMs / 1048576.0));
                UpdatePlan.Operation operation = new UpdatePlan.Operation(modId, local == null ? UpdatePlan.Kind.ADD : UpdatePlan.Kind.REPLACE,
                        local == null ? null : local.jar().getFileName().toString(), oldHash,
                        name, target.sha512(), target.fileSize());
                StagedFileVerifier.verifyAndStage(part, staged, target, session, cancelled);
                workspace.add(operation);
                LOGGER.info("{}verify READY modId={} route={} hash={}", prefix, modId, route,
                        target.sha512().substring(0, 12));
            } catch (InterruptedException exception) {
                cleanupPart(part);
                throw exception;
            } catch (IOException | RuntimeException exception) {
                cleanupPart(part);
                if (cancelled.getAsBoolean()) throw new InterruptedException("Sync cancelled");
                boolean verifying = session.snapshot().items().stream().anyMatch(state ->
                        state.item().comparison().required().modId().equals(modId)
                                && (state.state() == JaneSyncSession.RuntimeState.VERIFYING
                                || state.state() == JaneSyncSession.RuntimeState.FAILED));
                session.update(modId, JaneSyncSession.RuntimeState.FAILED, received.get());
                LOGGER.warn("{}{} failed modId={} route={}", prefix, verifying ? "verify" : "download",
                        modId, route, exception);
            }
        }
        if (cancelled.getAsBoolean()) throw new InterruptedException("Sync cancelled");
        UpdatePlan plan = workspace.prepareIfComplete(gameDir, session, cancelled);
        if (plan == null) {
            LOGGER.info("{}batch complete pending withheld unresolved={} lookupFailed={} ready={} requested={}", prefix,
                    resolution.count(ResolutionPlan.Availability.UNRESOLVED),
                    resolution.count(ResolutionPlan.Availability.LOOKUP_FAILED), session.snapshot().readyCount(), queue.size());
            return new Outcome(null);
        }
        LOGGER.info("{}workspace ready operations={}", prefix, workspace.operations().size());
        return new Outcome(plan);
    }

    static String fileName(ResolutionPlan.Item item) {
        return item.availability() == ResolutionPlan.Availability.TRUSTED_AVAILABLE
                ? item.trustedSource().name() : ResolutionPlan.serverSource(item.comparison().required()).name();
    }

    static void cleanupPart(Path part) {
        try { Files.deleteIfExists(part); }
        catch (IOException exception) { LOGGER.warn("Jane could not remove incomplete download", exception); }
    }

    private static void validateDestination(Path gameDir, ResolutionPlan.Item item, String fileName,
                                            Map<String, Integer> fileNames,
                                            Set<String> oldNames) throws IOException {
        String name = PathSafety.safeJarName(fileName);
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
