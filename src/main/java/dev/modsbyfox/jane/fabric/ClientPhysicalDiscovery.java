package dev.modsbyfox.jane.fabric;

import dev.modsbyfox.jane.core.Comparison;
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
import java.util.TreeMap;
import net.fabricmc.loader.api.metadata.ModEnvironment;

/** Discovers only physical, direct-child Fabric JARs; nested Loader containers are never inspected. */
public final class ClientPhysicalDiscovery {
    public record PhysicalClientMod(String modId, String displayName, String version,
                                    ModEnvironment environment, Path jar) { }
    public record Discovery(Map<String, List<Comparison.LocalMod>> candidates, List<PhysicalClientMod> all) {
        public Discovery {
            candidates = Map.copyOf(candidates);
            all = List.copyOf(all);
        }
    }
    private record Candidate(FabricJarMetadata.PhysicalMetadata metadata, Path jar) { }

    private ClientPhysicalDiscovery() { }

    public static Discovery scan(Path gameDir) throws IOException {
        Path root = gameDir.toRealPath();
        Path mods = root.resolve("mods");
        if (!Files.exists(mods, LinkOption.NOFOLLOW_LINKS)) return new Discovery(Map.of(), List.of());
        if (Files.isSymbolicLink(mods) || !Files.isDirectory(mods, LinkOption.NOFOLLOW_LINKS)
                || !mods.toRealPath().getParent().equals(root))
            throw new IOException("mods directory escaped current instance");
        List<Path> jars = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(mods)) {
            for (Path entry : entries) {
                if (entry.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) jars.add(entry);
            }
        }
        jars.sort(Comparator.comparing(path -> path.getFileName().toString()));
        Map<String, List<Candidate>> byId = new TreeMap<>();
        List<PhysicalClientMod> all = new ArrayList<>();
        for (Path jar : jars) {
            Path safe = PathSafety.existingJarInMods(root, jar);
            FabricJarMetadata.readPhysical(safe).ifPresent(metadata -> {
                byId.computeIfAbsent(metadata.modId(), ignored -> new ArrayList<>())
                        .add(new Candidate(metadata, safe));
                all.add(new PhysicalClientMod(metadata.modId(), metadata.displayName(), metadata.version(),
                        metadata.environment(), safe));
            });
        }
        Map<String, List<Comparison.LocalMod>> candidates = new TreeMap<>();
        for (var group : byId.entrySet()) {
            candidates.put(group.getKey(), group.getValue().stream().map(candidate ->
                    new Comparison.LocalMod(group.getKey(), candidate.metadata().version(), candidate.jar())).toList());
        }
        return new Discovery(candidates, all);
    }
}
