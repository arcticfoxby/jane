package dev.modsbyfox.jane.fabric;

import static org.junit.jupiter.api.Assertions.*;

import dev.modsbyfox.jane.core.ClientCompatibilityReport;
import dev.modsbyfox.jane.core.Comparison;
import dev.modsbyfox.jane.core.Hashing;
import dev.modsbyfox.jane.core.ManifestEntry;
import dev.modsbyfox.jane.core.RequiredManifest;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientCompatibilityTest {
    @TempDir Path game;

    private ManifestEntry required(Path jar, String id) throws Exception {
        return new ManifestEntry(id, id, "1.2.3", java.nio.file.Files.size(jar), Hashing.sha512(jar));
    }

    @Test void classifiesOnlyPhysicalExtrasOutsideRequiredIds() throws Exception {
        Path mods = game.resolve("mods");
        Path create = PhysicalJarFixture.mod(mods, "create.jar", "create", "*");
        Path fabric = PhysicalJarFixture.mod(mods, "fabric-api.jar", "fabric-api", "*");
        Path requiredClient = PhysicalJarFixture.mod(mods, "clienthelper.jar", "clienthelper", "client");
        PhysicalJarFixture.mod(mods, "old-create.jar", "create", "client");
        PhysicalJarFixture.mod(mods, "sodium.jar", "sodium", "client");
        PhysicalJarFixture.mod(mods, "iris.jar", "iris", "client");
        PhysicalJarFixture.mod(mods, "xaero.jar", "xaero", "client");
        PhysicalJarFixture.mod(mods, "clientlib.jar", "clientlib", "*");
        PhysicalJarFixture.mod(mods, "jane.jar", "jane", "client");
        PhysicalJarFixture.jar(mods, "ordinary.jar", null);
        RequiredManifest manifest = new RequiredManifest(RequiredManifest.PROTOCOL,
                List.of(required(create, "create"), required(fabric, "fabric-api"),
                        required(requiredClient, "clienthelper")));
        var discovery = ClientPhysicalDiscovery.scan(game);
        var compared = Comparison.compareCandidates(manifest, discovery.candidates(), Hashing::sha512);
        assertTrue(Comparison.passed(compared));
        assertEquals(1, compared.get(0).retainedCandidates().size());
        ClientCompatibilityReport report = ClientCompatibility.classify(manifest, discovery, Hashing::sha512);
        assertEquals(List.of("iris", "sodium", "xaero"), report.explicitClientMods().stream()
                .map(ClientCompatibilityReport.ExtraMod::modId).toList());
        assertEquals(List.of("clientlib"), report.otherExtraMods().stream()
                .map(ClientCompatibilityReport.ExtraMod::modId).toList());
        assertNull(report.otherExtraMods().get(0).sha512());
        assertFalse(report.explicitClientMods().stream().anyMatch(mod -> mod.modId().equals("clienthelper")
                || mod.modId().equals("create") || mod.modId().equals("jane")));
    }

    @Test void explicitFingerprintChangesOnHashAdditionAndRemoval() throws Exception {
        Path mods = game.resolve("mods");
        PhysicalJarFixture.mod(mods, "sodium.jar", "sodium", "client");
        RequiredManifest empty = new RequiredManifest(RequiredManifest.PROTOCOL, List.of());
        var first = ClientCompatibility.classify(empty, ClientPhysicalDiscovery.scan(game), Hashing::sha512);
        assertEquals(Hashing.sha512(mods.resolve("sodium.jar")), first.explicitClientMods().get(0).sha512());
        PhysicalJarFixture.mod(mods, "iris.jar", "iris", "client");
        var added = ClientCompatibility.classify(empty, ClientPhysicalDiscovery.scan(game), Hashing::sha512);
        assertNotEquals(first.fingerprint(), added.fingerprint());
        var reversed = new java.util.ArrayList<>(added.explicitClientMods());
        java.util.Collections.reverse(reversed);
        assertEquals(added.fingerprint(), ClientCompatibilityReport.fingerprint(reversed));
        java.nio.file.Files.delete(mods.resolve("iris.jar"));
        var removed = ClientCompatibility.classify(empty, ClientPhysicalDiscovery.scan(game), Hashing::sha512);
        assertEquals(first.fingerprint(), removed.fingerprint());
        try (var output = new java.util.zip.ZipOutputStream(
                java.nio.file.Files.newOutputStream(mods.resolve("sodium.jar")))) {
            output.putNextEntry(new java.util.zip.ZipEntry("fabric.mod.json"));
            output.write("{\"schemaVersion\":1,\"id\":\"sodium\",\"version\":\"1.2.3\",\"environment\":\"client\"}"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
            output.putNextEntry(new java.util.zip.ZipEntry("changed.txt"));
            output.write("different file bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
        var changed = ClientCompatibility.classify(empty, ClientPhysicalDiscovery.scan(game), Hashing::sha512);
        assertEquals(first.explicitClientMods().get(0).version(), changed.explicitClientMods().get(0).version());
        assertNotEquals(first.fingerprint(), changed.fingerprint());
    }

    @Test void universalExtraNeverInvokesHasher() throws Exception {
        Path mods = game.resolve("mods");
        Path explicit = PhysicalJarFixture.mod(mods, "sodium.jar", "sodium", "client");
        Path universal = PhysicalJarFixture.mod(mods, "clientlib.jar", "clientlib", "*");
        RequiredManifest empty = new RequiredManifest(RequiredManifest.PROTOCOL, List.of());
        var report = ClientCompatibility.classify(empty, ClientPhysicalDiscovery.scan(game), path -> {
            if (path.equals(universal)) throw new java.io.IOException("universal extra cannot be hashed");
            return Hashing.sha512(path);
        });
        assertEquals(Hashing.sha512(explicit), report.explicitClientMods().get(0).sha512());
        assertEquals(List.of("clientlib"), report.otherExtraMods().stream()
                .map(ClientCompatibilityReport.ExtraMod::modId).toList());
        assertNull(report.otherExtraMods().get(0).sha512());
    }
}
