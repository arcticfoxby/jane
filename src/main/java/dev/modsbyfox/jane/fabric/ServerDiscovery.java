package dev.modsbyfox.jane.fabric;

import dev.modsbyfox.jane.core.PathSafety;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.loader.api.metadata.ModEnvironment;

/** Scans only physical JARs directly in this instance's mods directory. */
final class ServerDiscovery {
    private static final System.Logger LOGGER = System.getLogger("jane");

    record Candidate(String modId, String displayName, String version, ModEnvironment fabricEnvironment) { }
    record Discovered(Candidate candidate, Path jar) { }
    record Skipped(Path jar, String reason) { }
    record Discovery(List<Discovered> mods, List<Skipped> skipped) { }

    private ServerDiscovery() { }

    static Discovery discover(Path gameDir) throws IOException {
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
        List<Discovered> discovered = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        Map<String, Path> ownerById = new HashMap<>();
        for (Path jar : jars) {
            Path safe = PathSafety.existingJarInMods(root, jar);
            Optional<Candidate> metadata = FabricJarMetadata.read(safe);
            if (metadata.isEmpty()) {
                skipped.add(new Skipped(safe, "not_fabric_mod"));
                LOGGER.log(System.Logger.Level.WARNING, "Jane skipped JAR without root fabric.mod.json: "
                        + safe.getFileName());
                continue;
            }
            Candidate candidate = metadata.orElseThrow();
            Path previous = ownerById.putIfAbsent(candidate.modId(), safe);
            if (previous != null) throw new IOException("Duplicate physical Mod ID: " + candidate.modId());
            discovered.add(new Discovered(candidate, safe));
        }
        discovered.sort(Comparator.comparing((Discovered item) -> item.candidate().modId())
                .thenComparing(item -> item.jar().getFileName().toString()));
        return new Discovery(List.copyOf(discovered), List.copyOf(skipped));
    }
}
