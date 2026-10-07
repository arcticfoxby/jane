package dev.modsbyfox.jane.fabric.client;

import dev.modsbyfox.jane.core.Comparison;
import dev.modsbyfox.jane.core.HashCache;
import dev.modsbyfox.jane.core.Hashing;
import dev.modsbyfox.jane.core.PathSafety;
import dev.modsbyfox.jane.core.RequiredManifest;
import dev.modsbyfox.jane.fabric.ClientCompatibility;
import dev.modsbyfox.jane.fabric.ClientPhysicalDiscovery;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** One physical scan feeds both the server baseline and local-only compatibility review. */
final class ClientAssessmentService {
    private ClientAssessmentService() { }

    static ClientAssessment assess(Path gameDir, RequiredManifest manifest) throws IOException {
        ClientPhysicalDiscovery.Discovery discovery = ClientPhysicalDiscovery.scan(gameDir);
        Comparison.FileHasher hasher;
        try {
            HashCache cache = new HashCache(gameDir);
            hasher = path -> cache.hash(PathSafety.existingJarInMods(gameDir, path));
        } catch (IOException exception) {
            hasher = path -> Hashing.sha512(PathSafety.existingJarInMods(gameDir, path));
        }
        return assess(manifest, discovery, hasher);
    }

    static ClientAssessment assess(RequiredManifest manifest, ClientPhysicalDiscovery.Discovery discovery,
                                   Comparison.FileHasher hasher) throws IOException {
        List<Comparison.Result> results = Comparison.compareCandidates(manifest, discovery.candidates(), hasher);
        return new ClientAssessment(discovery, results, Comparison.passed(results)
                ? ClientCompatibility.classify(manifest, discovery, hasher) : null);
    }
}
