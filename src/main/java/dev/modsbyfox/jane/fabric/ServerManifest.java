package dev.modsbyfox.jane.fabric;

import dev.modsbyfox.jane.core.EnvironmentRules;
import dev.modsbyfox.jane.core.Hashing;
import dev.modsbyfox.jane.core.ManifestEntry;
import dev.modsbyfox.jane.core.RequiredEnvironment;
import dev.modsbyfox.jane.core.RequiredManifest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModEnvironment;

final class ServerManifest {
    private static final System.Logger LOGGER = System.getLogger("jane");

    @FunctionalInterface interface JarReader { JarData read() throws IOException; }
    record JarData(long size, String sha512) { }
    record Candidate(ModEnvironment fabricEnvironment, String displayName, String version, JarReader jarReader) { }
    @FunctionalInterface interface CandidateProvider { Candidate load(String modId) throws IOException; }
    @FunctionalInterface interface BatchLookup { Map<String, String> lookup(Set<String> hashes) throws IOException; }
    private record Pending(String modId, ManifestEntry entry) { }

    private ServerManifest() { }

    static RequiredManifest build() throws IOException {
        FabricLoader loader = FabricLoader.getInstance();
        Path gameDir = loader.getGameDir();
        ServerConfig.Config config = ServerConfig.read(gameDir, loader.getConfigDir());
        return build(config, id -> {
            ModContainer mod = loader.getModContainer(id)
                    .orElseThrow(() -> new IOException("Required mod not loaded: " + id));
            return new Candidate(mod.getMetadata().getEnvironment(), mod.getMetadata().getName(),
                    mod.getMetadata().getVersion().getFriendlyString(), () -> readJar(mod, gameDir, id));
        }, new ModrinthEnvironmentService()::lookup);
    }

    static RequiredManifest build(ServerConfig.Config config, CandidateProvider provider,
                                  BatchLookup batchLookup) throws IOException {
        List<ManifestEntry> required = new ArrayList<>();
        List<Pending> universal = new ArrayList<>();
        Set<String> hashes = new LinkedHashSet<>();
        int serverOnly = 0;
        int clientOptional = 0;
        int overridden = 0;
        for (String id : config.requiredMods()) {
            Candidate candidate = provider.load(id);
            boolean override = config.environmentOverrides().containsKey(id);
            var fabricResult = ServerEnvironmentClassifier.fromFabric(candidate.fabricEnvironment());
            if (fabricResult.isPresent()) {
                RequiredEnvironment automatic = fabricResult.get();
                LOGGER.log(System.Logger.Level.INFO, "Jane environment: " + id + " -> " + automatic + " (fabric: "
                        + candidate.fabricEnvironment().name().toLowerCase(java.util.Locale.ROOT) + ")");
                if (automatic == RequiredEnvironment.CLIENT_OPTIONAL) {
                    LOGGER.log(System.Logger.Level.WARNING, "Client-only mod " + id + " is listed in dedicated-server requiredMods");
                }
                EnvironmentRules.applyOverride(id, automatic, override);
                if (automatic == RequiredEnvironment.SERVER_ONLY) serverOnly++;
                else clientOptional++;
                continue;
            }
            JarData jar = candidate.jarReader().read();
            ManifestEntry entry = new ManifestEntry(id, candidate.displayName(), candidate.version(),
                    jar.size(), jar.sha512());
            universal.add(new Pending(id, entry));
            hashes.add(entry.sha512());
        }
        Map<String, String> environments = hashes.isEmpty() ? Map.of() : batchLookup.lookup(hashes);
        for (Pending item : universal) {
            String raw = environments.get(item.entry().sha512());
            RequiredEnvironment automatic = EnvironmentRules.fromModrinth(raw);
            boolean override = config.environmentOverrides().containsKey(item.modId());
            String source = raw == null ? "modrinth: hash not found" : "modrinth: " + raw;
            LOGGER.log(System.Logger.Level.INFO, "Jane environment: " + item.modId() + " -> " + automatic + " (" + source + ")");
            RequiredEnvironment classification = EnvironmentRules.applyOverride(item.modId(), automatic, override);
            if (override) LOGGER.log(System.Logger.Level.INFO,
                    "Jane environment: " + item.modId() + " -> CLIENT_REQUIRED (administrator override)");
            switch (classification) {
                case CLIENT_REQUIRED -> {
                    required.add(item.entry());
                    if (override) overridden++;
                }
                case SERVER_ONLY -> serverOnly++;
                case CLIENT_OPTIONAL -> clientOptional++;
                case UNKNOWN -> throw new IOException("Unresolved environment for " + item.modId());
            }
        }
        LOGGER.log(System.Logger.Level.INFO, "Jane environment summary: candidates=" + config.requiredMods().size()
                + ", clientRequired=" + required.size() + ", serverOnly=" + serverOnly + ", clientOptional=" + clientOptional
                + ", overridden=" + overridden);
        return new RequiredManifest(RequiredManifest.PROTOCOL, required);
    }

    private static JarData readJar(ModContainer mod, Path gameDir, String id) throws IOException {
        Path jar = ModOrigins.directJar(mod, gameDir);
        long size = Files.size(jar);
        long modified = Files.getLastModifiedTime(jar).toMillis();
        String hash = Hashing.sha512(jar);
        if (Files.size(jar) != size || Files.getLastModifiedTime(jar).toMillis() != modified) {
            throw new IOException("Required JAR changed while hashing: " + id);
        }
        return new JarData(size, hash);
    }
}
