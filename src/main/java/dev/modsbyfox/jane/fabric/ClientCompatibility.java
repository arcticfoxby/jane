package dev.modsbyfox.jane.fabric;

import dev.modsbyfox.jane.core.ClientCompatibilityReport;
import dev.modsbyfox.jane.core.Comparison;
import dev.modsbyfox.jane.core.RequiredManifest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.loader.api.metadata.ModEnvironment;

/** Classifies only top-level Fabric JARs discovered in the same physical scan used for comparison. */
public final class ClientCompatibility {
    private ClientCompatibility() { }

    public static ClientCompatibilityReport classify(RequiredManifest manifest,
                                                      ClientPhysicalDiscovery.Discovery discovery,
                                                      Comparison.FileHasher hasher) throws IOException {
        Set<String> requiredIds = new HashSet<>();
        manifest.entries().forEach(entry -> requiredIds.add(entry.modId()));
        List<ClientCompatibilityReport.ExtraMod> explicit = new ArrayList<>();
        List<ClientCompatibilityReport.ExtraMod> other = new ArrayList<>();
        for (ClientPhysicalDiscovery.PhysicalClientMod mod : discovery.all()) {
            if (requiredIds.contains(mod.modId()) || "jane".equals(mod.modId())) continue;
            ClientCompatibilityReport.Kind kind = mod.environment() == ModEnvironment.CLIENT
                    ? ClientCompatibilityReport.Kind.EXPLICIT_CLIENT
                    : mod.environment() == ModEnvironment.UNIVERSAL
                    ? ClientCompatibilityReport.Kind.OTHER_EXTRA : null;
            if (kind == null) continue;
            String hash = null;
            if (kind == ClientCompatibilityReport.Kind.EXPLICIT_CLIENT) {
                try {
                    String candidate = hasher.hash(mod.jar());
                    if (candidate != null && candidate.matches("[0-9a-f]{128}")) hash = candidate;
                } catch (IOException | IllegalArgumentException exception) {
                    // An extra JAR cannot invalidate a completed required-file comparison.
                    // Without a hash it remains visible, but cannot be selected for safe disable.
                }
            }
            ClientCompatibilityReport.ExtraMod extra = new ClientCompatibilityReport.ExtraMod(
                    mod.modId(), mod.displayName(), mod.version(), mod.jar().getFileName().toString(),
                    hash, kind, mod.jar());
            if (kind == ClientCompatibilityReport.Kind.EXPLICIT_CLIENT) explicit.add(extra);
            else other.add(extra);
        }
        return new ClientCompatibilityReport(explicit, other, ClientCompatibilityReport.fingerprint(explicit));
    }
}
