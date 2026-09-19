package dev.modsbyfox.jane.fabric;

import dev.modsbyfox.jane.core.ClientSyncDecision;
import dev.modsbyfox.jane.core.EnvironmentRules;
import dev.modsbyfox.jane.core.Hashing;
import dev.modsbyfox.jane.core.ManifestEntry;
import dev.modsbyfox.jane.core.RequiredManifest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.metadata.ModEnvironment;

final class ServerManifest {
    private static final System.Logger LOGGER = System.getLogger("jane");
    private static boolean legacyWarned;

    record JarData(long size, String sha512) { }
    @FunctionalInterface interface BatchLookup { Map<String, String> lookup(Set<String> hashes) throws IOException; }
    record Classified(ServerDiscovery.Discovered discovered, JarData jar, String modrinthEnvironment,
                      ClientSyncDecision decision, String reason) { }
    record BuildResult(RequiredManifest manifest, List<Classified> classified) { }
    record Prepared(RequiredManifest manifest, Map<String, Path> files) { }
    private record Hashed(ServerDiscovery.Discovered discovered, JarData jar) { }

    private ServerManifest() { }

    static RequiredManifest build() throws IOException {
        return prepare().manifest();
    }

    static Prepared prepare() throws IOException {
        FabricLoader loader = FabricLoader.getInstance();
        Path gameDir = loader.getGameDir();
        ServerConfig.Config config = ServerConfig.read(gameDir, loader.getConfigDir());
        if (config.legacyFieldsPresent() && !legacyWarned) {
            legacyWarned = true;
            LOGGER.log(System.Logger.Level.WARNING, "Jane ignores legacy requiredMods and environmentOverrides; AUTO_DISCOVER is active");
        }
        ServerDiscovery.Discovery discovery = ServerDiscovery.discover(loader);
        BuildResult result = build(discovery.mods(), new ModrinthEnvironmentService()::lookup);
        DiscoveredModsStore.write(gameDir, result.classified());
        long sync = result.classified().stream().filter(item -> item.decision() == ClientSyncDecision.SYNC).count();
        long conservative = result.classified().stream().filter(item -> item.decision() == ClientSyncDecision.SYNC_CONSERVATIVE).count();
        LOGGER.log(System.Logger.Level.INFO, "Jane discovery summary: discoveredTopLevelJars=" + discovery.mods().size()
                + ", sync=" + sync + ", syncConservative=" + conservative
                + ", excluded=" + (result.classified().size() - sync - conservative)
                + ", nestedSkipped=" + discovery.nestedSkipped() + ", systemSkipped=" + discovery.systemSkipped()
                + ", manifestEntries=" + result.manifest().entries().size());
        Map<String, Path> files = new java.util.HashMap<>();
        Set<String> requiredIds = result.manifest().entries().stream().map(ManifestEntry::modId)
                .collect(java.util.stream.Collectors.toSet());
        for (Classified item : result.classified()) {
            if (requiredIds.contains(item.discovered().candidate().modId())) {
                files.put(item.jar().sha512(), item.discovered().jar());
            }
        }
        return new Prepared(result.manifest(), Map.copyOf(files));
    }

    static BuildResult build(List<ServerDiscovery.Discovered> discovered, BatchLookup lookup) throws IOException {
        List<Classified> classified = new ArrayList<>();
        List<Hashed> universal = new ArrayList<>();
        Set<String> hashes = new LinkedHashSet<>();
        for (ServerDiscovery.Discovered item : discovered) {
            ModEnvironment fabric = item.candidate().fabricEnvironment();
            if (ServerEnvironmentClassifier.fromFabric(fabric).isPresent()) {
                if (fabric == ModEnvironment.CLIENT) LOGGER.log(System.Logger.Level.WARNING,
                        "Client-only mod is loaded on the dedicated server: " + item.candidate().modId());
                classified.add(new Classified(item, null, null, ClientSyncDecision.EXCLUDE,
                        fabric == ModEnvironment.SERVER ? "fabric_server_only" : "fabric_client_only"));
                LOGGER.log(System.Logger.Level.INFO, "Jane discovery: " + item.candidate().modId()
                        + " -> EXCLUDE (fabric: " + fabric.name().toLowerCase(java.util.Locale.ROOT) + ")");
                continue;
            }
            JarData jar = readJar(item.jar(), item.candidate().modId());
            universal.add(new Hashed(item, jar));
            hashes.add(jar.sha512());
        }
        // An absent key in a successful response is a valid conservative result. A failed request is not.
        Map<String, String> environments = hashes.isEmpty() ? Map.of() : lookup.lookup(hashes);
        if (environments == null) throw new IOException("Modrinth lookup returned no result");
        List<ManifestEntry> required = new ArrayList<>();
        for (Hashed item : universal) {
            String raw = environments.get(item.jar().sha512());
            ClientSyncDecision decision = EnvironmentRules.fromModrinth(raw);
            String reason = raw == null ? "modrinth_hash_absent"
                    : raw.matches("[a-z0-9_]{1,64}") ? "modrinth_" + raw : "modrinth_unrecognized_environment";
            if ("client_only".equals(raw) || "singleplayer_only".equals(raw)) LOGGER.log(System.Logger.Level.WARNING,
                    "Unexpected Modrinth environment for dedicated-server mod " + item.discovered().candidate().modId()
                            + ": " + raw);
            if (EnvironmentRules.unknownFutureValue(raw)) LOGGER.log(System.Logger.Level.WARNING,
                    "Unknown Modrinth environment for " + item.discovered().candidate().modId() + "; syncing conservatively");
            classified.add(new Classified(item.discovered(), item.jar(), raw, decision, reason));
            LOGGER.log(System.Logger.Level.INFO, "Jane discovery: " + item.discovered().candidate().modId()
                    + " -> " + decision + " (" + reason + ")");
            if (decision.entersManifest()) {
                try {
                    ServerDiscovery.Candidate candidate = item.discovered().candidate();
                    required.add(new ManifestEntry(candidate.modId(), candidate.displayName(), candidate.version(),
                            item.jar().size(), item.jar().sha512()));
                } catch (IllegalArgumentException exception) {
                    throw new IOException("Invalid discovered manifest entry: " + item.discovered().candidate().modId(), exception);
                }
                if (required.size() > RequiredManifest.MAX_ENTRIES) {
                    throw new IOException("Jane discovered more client-sync entries than Protocol 3 supports");
                }
            }
        }
        classified.sort(java.util.Comparator.comparing(item -> item.discovered().candidate().modId()));
        try {
            RequiredManifest manifest = new RequiredManifest(RequiredManifest.PROTOCOL, required);
            LOGGER.log(System.Logger.Level.INFO, "Jane automatic discovery: " + classified.size() + " top-level mods, "
                    + required.size() + " client-sync entries");
            return new BuildResult(manifest, List.copyOf(classified));
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid automatically discovered manifest", exception);
        }
    }

    private static JarData readJar(Path jar, String id) throws IOException {
        long size = Files.size(jar);
        FileTime modified = Files.getLastModifiedTime(jar);
        String hash = Hashing.sha512(jar);
        if (Files.size(jar) != size || !Files.getLastModifiedTime(jar).equals(modified)) {
            throw new IOException("Required JAR changed while hashing: " + id);
        }
        return new JarData(size, hash);
    }
}
