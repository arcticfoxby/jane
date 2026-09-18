package dev.modsbyfox.jane.fabric;

import dev.modsbyfox.jane.core.ManifestEntry;
import dev.modsbyfox.jane.core.PathSafety;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModEnvironment;
import net.fabricmc.loader.api.metadata.ModOrigin;

/** Discovers physical top-level JARs from the loader, without scanning the mods directory. */
final class ServerDiscovery {
    private static final Set<String> INTERNAL = Set.of("jane", "fabric-api", "fabricloader", "minecraft");

    record Candidate(String modId, String displayName, String version, ModEnvironment fabricEnvironment,
                     boolean nested, List<Path> originPaths) { }
    record Discovered(Candidate candidate, Path jar) { }
    record Discovery(List<Discovered> mods, int nestedSkipped, int systemSkipped) { }

    private ServerDiscovery() { }

    static Discovery discover(FabricLoader loader) throws IOException {
        List<Candidate> candidates = new ArrayList<>();
        for (ModContainer mod : loader.getAllMods()) {
            ModOrigin origin = mod.getOrigin();
            candidates.add(new Candidate(mod.getMetadata().getId(), mod.getMetadata().getName(),
                    mod.getMetadata().getVersion().getFriendlyString(), mod.getMetadata().getEnvironment(),
                    mod.getContainingMod().isPresent(),
                    origin.getKind() == ModOrigin.Kind.PATH ? origin.getPaths() : List.of()));
        }
        return discoverWithStats(candidates, loader.getGameDir());
    }

    static List<Discovered> discover(Collection<Candidate> candidates, Path gameDir) throws IOException {
        return discoverWithStats(candidates, gameDir).mods();
    }

    static Discovery discoverWithStats(Collection<Candidate> candidates, Path gameDir) throws IOException {
        Path mods = gameDir.toAbsolutePath().normalize().resolve("mods");
        Path realMods = gameDir.toRealPath().resolve("mods");
        Map<Path, String> ownerByJar = new HashMap<>();
        List<Discovered> result = new ArrayList<>();
        int nestedSkipped = 0;
        int systemSkipped = 0;
        for (Candidate candidate : candidates) {
            if (candidate.nested()) { nestedSkipped++; continue; }
            if (INTERNAL.contains(candidate.modId()) || candidate.originPaths().isEmpty()) { systemSkipped++; continue; }
            // Check lexical scope first: loader builtins and other runtime libraries must not be opened.
            List<Path> inMods = candidate.originPaths().stream()
                    .filter(path -> path.toAbsolutePath().normalize().getParent() != null
                            && (path.toAbsolutePath().normalize().getParent().equals(mods)
                            || path.toAbsolutePath().normalize().getParent().equals(realMods))).toList();
            if (inMods.isEmpty()) { systemSkipped++; continue; }
            if (candidate.originPaths().size() != 1) throw new IOException("Ambiguous origin paths for " + candidate.modId());
            if (!ManifestEntry.validId(candidate.modId())) throw new IOException("Invalid discovered Mod ID: " + candidate.modId());
            Path jar = PathSafety.existingJarInMods(gameDir, inMods.get(0));
            String previous = ownerByJar.putIfAbsent(jar, candidate.modId());
            if (previous != null) throw new IOException("Ambiguous top-level mod identities for one JAR: "
                    + previous + ", " + candidate.modId());
            result.add(new Discovered(candidate, jar));
        }
        result.sort(Comparator.comparing(item -> item.candidate().modId()));
        return new Discovery(List.copyOf(result), nestedSkipped, systemSkipped);
    }
}
