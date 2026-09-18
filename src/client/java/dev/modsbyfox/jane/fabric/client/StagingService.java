package dev.modsbyfox.jane.fabric.client;

import dev.modsbyfox.jane.core.Comparison;
import dev.modsbyfox.jane.core.BackupTimestamp;
import dev.modsbyfox.jane.core.Hashing;
import dev.modsbyfox.jane.core.ManifestEntry;
import dev.modsbyfox.jane.core.PathSafety;
import dev.modsbyfox.jane.core.PendingStore;
import dev.modsbyfox.jane.core.PendingSyncContext;
import dev.modsbyfox.jane.core.UpdatePlan;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class StagingService {
    private static final Logger LOGGER = LoggerFactory.getLogger("jane");
    record Outcome(UpdatePlan plan, List<ManifestEntry> unavailable) { }
    private record Resolved(Comparison.Result result, ModrinthService.Download download) { }

    private StagingService() { }

    static Outcome stage(PendingSyncContext session, Path gameDir, BooleanSupplier cancelled) throws IOException, InterruptedException {
        LOGGER.info("Using captured Jane server context {} for staging", session.serverId().substring(0, 12));
        ModrinthService modrinth = new ModrinthService();
        List<Resolved> resolved = new ArrayList<>();
        List<ManifestEntry> unavailable = new ArrayList<>();
        for (Comparison.Result result : session.results()) {
            if (cancelled.getAsBoolean()) throw new IOException("Sync cancelled");
            if (result.status() == Comparison.Status.OK) continue;
            if (result.status() == Comparison.Status.FILE_ERROR) {
                unavailable.add(result.required());
                continue;
            }
            var source = modrinth.find(result.required());
            if (cancelled.getAsBoolean()) throw new IOException("Sync cancelled");
            if (source.isPresent()) resolved.add(new Resolved(result, source.get()));
            else unavailable.add(result.required());
        }
        if (!unavailable.isEmpty()) return new Outcome(null, List.copyOf(unavailable));
        Set<String> newNames = new HashSet<>();
        Set<String> oldNames = new HashSet<>();
        for (Resolved item : resolved) {
            if (!newNames.add(item.download().name().toLowerCase(Locale.ROOT))) throw new IOException("Duplicate download file name");
            if (item.result().local() != null) {
                Path old = PathSafety.existingJarInMods(gameDir, item.result().local().jar());
                oldNames.add(PathSafety.safeJarName(old.getFileName().toString()).toLowerCase(Locale.ROOT));
            }
        }
        for (Resolved item : resolved) {
            String newName = item.download().name();
            Path destination = PathSafety.newJarInMods(gameDir, newName);
            String oldName = item.result().local() == null ? null : item.result().local().jar().getFileName().toString();
            if (Files.exists(destination) && (oldName == null || !newName.equalsIgnoreCase(oldName))) {
                throw new IOException("Download would overwrite an unrelated local JAR");
            }
            if (oldNames.contains(newName.toLowerCase(Locale.ROOT)) && (oldName == null || !newName.equalsIgnoreCase(oldName))) {
                throw new IOException("Download file name conflicts with another required JAR");
            }
        }
        String syncId = UUID.randomUUID().toString();
        String timestamp = BackupTimestamp.next(gameDir, session.serverId());
        Path staging = PathSafety.janeDirectory(gameDir, "staging", syncId);
        List<UpdatePlan.Operation> operations = new ArrayList<>();
        try {
            for (Resolved item : resolved) {
                if (cancelled.getAsBoolean()) throw new IOException("Sync cancelled");
                ManifestEntry target = item.result().required();
                String name = item.download().name();
                Path part = staging.resolve(name + ".part");
                Path staged = staging.resolve(name);
                modrinth.download(item.download(), target, part, cancelled);
                if (!target.sha512().equals(Hashing.sha512(part))) throw new IOException("Downloaded JAR hash mismatch: " + target.modId());
                Files.move(part, staged, StandardCopyOption.ATOMIC_MOVE);
                Comparison.LocalMod local = item.result().local();
                operations.add(new UpdatePlan.Operation(target.modId(), local == null ? UpdatePlan.Kind.ADD : UpdatePlan.Kind.REPLACE,
                        local == null ? null : local.jar().getFileName().toString(), item.result().localHash(),
                        name, target.sha512(), target.fileSize()));
            }
            UpdatePlan plan = new UpdatePlan(syncId, session.serverId(), timestamp, operations);
            if (cancelled.getAsBoolean()) throw new IOException("Sync cancelled");
            PendingStore.create(gameDir, plan);
            return new Outcome(plan, List.of());
        } catch (IOException | RuntimeException | InterruptedException exception) {
            for (Resolved item : resolved) {
                Files.deleteIfExists(staging.resolve(item.download().name() + ".part"));
                Files.deleteIfExists(staging.resolve(item.download().name()));
            }
            Files.deleteIfExists(staging);
            throw exception;
        }
    }
}
