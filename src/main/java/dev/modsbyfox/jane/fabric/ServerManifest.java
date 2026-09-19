package dev.modsbyfox.jane.fabric;

import dev.modsbyfox.jane.core.ClientSyncDecision;
import dev.modsbyfox.jane.core.Hashing;
import dev.modsbyfox.jane.core.ManifestEntry;
import dev.modsbyfox.jane.core.PathSafety;
import dev.modsbyfox.jane.core.RequiredManifest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.metadata.ModEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The physical non-server-only Fabric JAR set is the required client baseline. */
final class ServerManifest {
    private static final Logger LOGGER = LoggerFactory.getLogger("jane");
    private static boolean legacyWarned;

    record JarData(long size, String sha512) { }
    record Classified(ServerDiscovery.Discovered discovered, JarData jar,
                      ClientSyncDecision decision, String reason) { }
    record BuildResult(RequiredManifest manifest, List<Classified> classified) { }
    record Prepared(RequiredManifest manifest, Map<String, Path> files) { }

    private ServerManifest() { }

    static RequiredManifest build() throws IOException { return prepare().manifest(); }

    static Prepared prepare() throws IOException {
        FabricLoader loader = FabricLoader.getInstance();
        Path gameDir = loader.getGameDir();
        ServerConfig.Config config = ServerConfig.read(gameDir, loader.getConfigDir());
        if (config.legacyFieldsPresent() && !legacyWarned) {
            legacyWarned = true;
            LOGGER.warn("{}ignores legacy requiredMods and environmentOverrides; AUTO_DISCOVER is active", JaneLog.server());
        }
        ServerDiscovery.Discovery discovery = ServerDiscovery.discover(gameDir);
        BuildResult result = build(discovery.mods(), gameDir);
        DiscoveredModsStore.write(gameDir, result.classified(), discovery.skipped());
        long sync = result.classified().stream().filter(item -> item.decision() == ClientSyncDecision.SYNC).count();
        LOGGER.info(JaneLog.server() + "discovery summary: physicalFabricJars=" + discovery.mods().size()
                + ", sync=" + sync + ", excluded=" + (result.classified().size() - sync)
                + ", skippedPhysicalJars=" + discovery.skipped().size()
                + ", manifestEntries=" + result.manifest().entries().size());
        Map<String, Path> files = new HashMap<>();
        for (Classified item : result.classified()) {
            if (item.decision() == ClientSyncDecision.SYNC)
                files.put(item.jar().sha512(), item.discovered().jar());
        }
        return new Prepared(result.manifest(), Map.copyOf(files));
    }

    static BuildResult build(List<ServerDiscovery.Discovered> discovered, Path gameDir) throws IOException {
        List<Classified> classified = new ArrayList<>();
        List<ManifestEntry> required = new ArrayList<>();
        for (ServerDiscovery.Discovered item : discovered.stream()
                .sorted(Comparator.comparing(value -> value.candidate().modId())).toList()) {
            ServerDiscovery.Candidate candidate = item.candidate();
            if (candidate.fabricEnvironment() == ModEnvironment.SERVER || "jane".equals(candidate.modId())) {
                String reason = "jane".equals(candidate.modId()) ? "jane_internal" : "fabric_server_only";
                classified.add(new Classified(item, null, ClientSyncDecision.EXCLUDE, reason));
                LOGGER.info("{}discovery modId={} decision=EXCLUDE reason={}", JaneLog.server(), candidate.modId(), reason);
                continue;
            }
            Path safe = PathSafety.existingJarInMods(gameDir, item.jar());
            JarData jar = readJar(safe, candidate.modId());
            try {
                required.add(new ManifestEntry(candidate.modId(), candidate.displayName(), candidate.version(),
                        jar.size(), jar.sha512()));
            } catch (IllegalArgumentException exception) {
                throw new IOException("Invalid discovered manifest entry: " + candidate.modId(), exception);
            }
            if (required.size() > RequiredManifest.MAX_ENTRIES)
                throw new IOException("Jane discovered more client-sync entries than Protocol 3 supports");
            classified.add(new Classified(item, jar, ClientSyncDecision.SYNC, "physical_client_target"));
            LOGGER.info("{}discovery modId={} decision=SYNC reason=physical_client_target", JaneLog.server(), candidate.modId());
        }
        try {
            RequiredManifest manifest = new RequiredManifest(RequiredManifest.PROTOCOL, required);
            LOGGER.info(JaneLog.server() + "physical discovery: " + classified.size() + " Fabric JARs, "
                    + required.size() + " client-sync entries");
            return new BuildResult(manifest, List.copyOf(classified));
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid physical required manifest", exception);
        }
    }

    private static JarData readJar(Path jar, String id) throws IOException {
        if (Files.isSymbolicLink(jar) || !Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Required JAR is not a regular file: " + id);
        long size = Files.size(jar);
        if (size <= 0 || size > ManifestEntry.MAX_FILE_SIZE)
            throw new IOException("Required JAR size is invalid: " + id);
        FileTime modified = Files.getLastModifiedTime(jar);
        String hash = Hashing.sha512(jar);
        if (Files.size(jar) != size || !Files.getLastModifiedTime(jar).equals(modified))
            throw new IOException("Required JAR changed while hashing: " + id);
        return new JarData(size, hash);
    }
}
