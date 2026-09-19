package dev.modsbyfox.jane.fabric;

import dev.modsbyfox.jane.core.PathSafety;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.SemanticVersion;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.ModEnvironment;

/** Scans only physical JARs directly in this instance's mods directory. */
final class ServerDiscovery {
    private static final System.Logger LOGGER = System.getLogger("jane");

    record Candidate(String modId, String displayName, String version, ModEnvironment fabricEnvironment) { }
    record Discovered(Candidate candidate, Path jar) { }
    record Skipped(Path jar, Candidate candidate, String reason) { }
    record Discovery(List<Discovered> mods, List<Skipped> skipped) { }
    @FunctionalInterface
    interface SelectedJarResolver {
        Optional<Path> selectedJar(String modId) throws IOException;
    }

    private ServerDiscovery() { }

    static Discovery discover(Path gameDir) throws IOException {
        return discover(gameDir, id -> {
            var active = FabricLoader.getInstance().getModContainer(id);
            return active.isEmpty() ? Optional.empty() : Optional.of(ModOrigins.directJar(active.orElseThrow(), gameDir));
        });
    }

    static Discovery discover(Path gameDir, SelectedJarResolver selectedJar) throws IOException {
        Path root = gameDir.toRealPath();
        Path mods = root.resolve("mods");
        if (!Files.exists(mods, LinkOption.NOFOLLOW_LINKS)) return new Discovery(List.of(), List.of());
        if (Files.isSymbolicLink(mods) || !Files.isDirectory(mods, LinkOption.NOFOLLOW_LINKS)
                || !mods.toRealPath().getParent().equals(root)) {
            throw new IOException("mods directory escaped current instance");
        }
        List<Path> jars = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(mods)) {
            for (Path entry : entries) {
                if (entry.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) jars.add(entry);
            }
        }
        jars.sort(Comparator.comparing(path -> path.getFileName().toString()));
        Map<String, List<Discovered>> byId = new TreeMap<>();
        List<Skipped> skipped = new ArrayList<>();
        for (Path jar : jars) {
            Path safe = PathSafety.existingJarInMods(root, jar);
            Optional<Candidate> metadata = FabricJarMetadata.read(safe);
            if (metadata.isEmpty()) {
                skipped.add(new Skipped(safe, null, "not_fabric_mod"));
                LOGGER.log(System.Logger.Level.WARNING, "Jane skipped JAR without root fabric.mod.json: "
                        + safe.getFileName());
                continue;
            }
            Candidate candidate = metadata.orElseThrow();
            byId.computeIfAbsent(candidate.modId(), ignored -> new ArrayList<>()).add(new Discovered(candidate, safe));
        }
        List<Discovered> discovered = new ArrayList<>();
        for (var group : byId.entrySet()) {
            List<Discovered> candidates = group.getValue();
            if (candidates.size() == 1) {
                discovered.add(candidates.get(0));
            } else {
                discovered.add(resolveCandidates(group.getKey(), candidates, root, selectedJar, skipped));
            }
        }
        discovered.sort(Comparator.comparing((Discovered item) -> item.candidate().modId())
                .thenComparing(item -> item.jar().getFileName().toString()));
        skipped.sort(Comparator.comparing(item -> item.jar().getFileName().toString()));
        return new Discovery(List.copyOf(discovered), List.copyOf(skipped));
    }

    private static Discovered resolveCandidates(String id, List<Discovered> candidates, Path root,
                                                 SelectedJarResolver selectedJar, List<Skipped> skipped)
            throws IOException {
        LOGGER.log(System.Logger.Level.INFO, "Jane duplicate resolution: " + id + " has "
                + candidates.size() + " physical candidates");
        String details = candidates.stream().map(item -> item.jar().getFileName() + " version "
                + item.candidate().version()).reduce((a, b) -> a + ", " + b).orElse("");
        Optional<Path> selected;
        try {
            selected = selectedJar.selectedJar(id);
        } catch (IOException exception) {
            throw new IOException("Cannot safely resolve duplicate physical Mod ID '" + id
                    + "': Fabric Loader selected origin unavailable; candidates: " + details, exception);
        }
        Discovered winner;
        String skippedReason;
        if (selected.isPresent()) {
            Path origin;
            try {
                origin = PathSafety.existingJarInMods(root, selected.orElseThrow());
            } catch (IOException exception) {
                throw new IOException("Cannot safely resolve duplicate physical Mod ID '" + id
                        + "': Fabric Loader selected origin is unsafe; candidates: " + details, exception);
            }
            winner = candidates.stream().filter(item -> item.jar().equals(origin)).findFirst()
                    .orElseThrow(() -> new IOException("Cannot safely resolve duplicate physical Mod ID '" + id
                            + "': Fabric Loader selected origin " + origin.getFileName()
                            + " is not a physical candidate; candidates: " + details));
            skippedReason = "duplicate_not_selected_by_loader";
            LOGGER.log(System.Logger.Level.INFO, "Jane duplicate resolution: " + id
                    + " -> selected by Fabric Loader: " + winner.jar().getFileName());
        } else {
            SemanticVersion highest = null;
            winner = null;
            boolean tied = false;
            for (Discovered item : candidates) {
                SemanticVersion version;
                try {
                    version = SemanticVersion.parse(item.candidate().version());
                } catch (VersionParsingException exception) {
                    throw new IOException("Cannot safely resolve duplicate physical Mod ID '" + id
                            + "': no active Fabric Loader candidate and a version is not a comparable SemanticVersion; "
                            + "candidates: " + details, exception);
                }
                int comparison = highest == null ? 1 : version.compareTo(highest);
                if (comparison > 0) {
                    highest = version;
                    winner = item;
                    tied = false;
                } else if (comparison == 0) {
                    tied = true;
                }
            }
            if (tied) throw new IOException("Cannot safely resolve duplicate physical Mod ID '" + id
                    + "': highest SemanticVersion is not unique; candidates: " + details);
            skippedReason = "duplicate_older_semver";
            LOGGER.log(System.Logger.Level.INFO, "Jane duplicate resolution: " + id
                    + " -> no active server candidate; selected newest SemanticVersion " + highest
                    + ": " + winner.jar().getFileName());
        }
        for (Discovered item : candidates) {
            if (item == winner) continue;
            skipped.add(new Skipped(item.jar(), item.candidate(), skippedReason));
            LOGGER.log(System.Logger.Level.INFO, "Jane duplicate resolution: " + id
                    + " -> skipped: " + item.jar().getFileName());
        }
        return winner;
    }
}
