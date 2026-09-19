package dev.modsbyfox.jane.fabric;

import static org.junit.jupiter.api.Assertions.*;

import dev.modsbyfox.jane.core.ClientSyncDecision;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerDiscoveryTest {
    @TempDir Path gameDir;

    private Path mods() { return gameDir.resolve("mods"); }

    @Test void scansOnlyDirectPhysicalFabricJarsInDeterministicOrder() throws Exception {
        PhysicalJarFixture.mod(mods(), "z.jar", "a", "*");
        PhysicalJarFixture.mod(mods(), "client.jar", "client", "client");
        PhysicalJarFixture.mod(mods(), "server.jar", "server", "server");
        PhysicalJarFixture.mod(mods(), "fabric-api.jar", "fabric-api", "*");
        PhysicalJarFixture.mod(mods(), "jane.jar", "jane", "*");
        PhysicalJarFixture.jar(mods(), "no-metadata.jar", null);
        PhysicalJarFixture.mod(mods().resolve("subdirectory"), "nested.jar", "nested", "*");
        Files.writeString(mods().resolve("not-a-jar.txt"), "ignored");

        ServerDiscovery.Discovery discovery = ServerDiscovery.discover(gameDir);
        assertEquals(List.of("a", "client", "fabric-api", "jane", "server"),
                discovery.mods().stream().map(item -> item.candidate().modId()).toList());
        assertEquals(List.of("no-metadata.jar"), discovery.skipped().stream()
                .map(item -> item.jar().getFileName().toString()).toList());
        assertEquals("not_fabric_mod", discovery.skipped().get(0).reason());
        ServerManifest.BuildResult result = ServerManifest.build(discovery.mods(), gameDir);
        assertEquals(List.of("a", "client", "fabric-api"), result.manifest().entries().stream()
                .map(entry -> entry.modId()).toList());
        Map<String, ClientSyncDecision> decisions = result.classified().stream().collect(Collectors.toMap(
                item -> item.discovered().candidate().modId(), ServerManifest.Classified::decision));
        assertEquals(ClientSyncDecision.SYNC, decisions.get("client"));
        assertEquals(ClientSyncDecision.SYNC, decisions.get("fabric-api"));
        assertEquals(ClientSyncDecision.EXCLUDE, decisions.get("server"));
        assertEquals(ClientSyncDecision.EXCLUDE, decisions.get("jane"));
    }

    @Test void malformedOrOversizedFabricMetadataFailsClosed() throws Exception {
        PhysicalJarFixture.jar(mods(), "bad.jar", "{broken");
        assertThrows(IOException.class, () -> ServerDiscovery.discover(gameDir));
        Files.delete(mods().resolve("bad.jar"));
        PhysicalJarFixture.jar(mods(), "bad.jar", "{\"schemaVersion\":1,\"id\":\"bad\",\"version\":\"1\","
                + "\"padding\":\"" + "x".repeat(1024 * 1024) + "\"}");
        assertThrows(IOException.class, () -> ServerDiscovery.discover(gameDir));
    }

    @Test void invalidIdentityEnvironmentAndDuplicateFieldsFailClosed() throws Exception {
        for (String metadata : List.of(
                "{\"schemaVersion\":1,\"id\":\"bad\",\"version\":\"1\",\"environment\":\"other\"}",
                "{\"schemaVersion\":1,\"id\":\"bad\",\"version\":null}",
                "{\"schemaVersion\":1,\"id\":\"bad\",\"id\":\"other\",\"version\":\"1\"}",
                "{\"schemaVersion\":1,\"id\":\"../bad\",\"version\":\"1\"}")) {
            Path bad = PhysicalJarFixture.jar(mods(), "bad.jar", metadata);
            assertThrows(IOException.class, () -> ServerDiscovery.discover(gameDir));
            Files.delete(bad);
        }
    }

    @Test void duplicatePhysicalModIdsFailClosed() throws Exception {
        PhysicalJarFixture.mod(mods(), "one.jar", "same", "*");
        PhysicalJarFixture.mod(mods(), "two.jar", "same", "client");
        IOException error = assertThrows(IOException.class, () -> ServerDiscovery.discover(gameDir));
        assertTrue(error.getMessage().contains("Duplicate physical Mod ID"));
    }

    @Test void missingEnvironmentDefaultsToUniversalAndMissingNameUsesId() throws Exception {
        PhysicalJarFixture.jar(mods(), "plain.jar", "{\"schemaVersion\":1,\"id\":\"plain\",\"version\":\"1\"}");
        ServerDiscovery.Discovery discovery = ServerDiscovery.discover(gameDir);
        assertEquals("plain", discovery.mods().get(0).candidate().displayName());
        assertEquals(net.fabricmc.loader.api.metadata.ModEnvironment.UNIVERSAL,
                discovery.mods().get(0).candidate().fabricEnvironment());
        assertEquals("plain", ServerManifest.build(discovery.mods(), gameDir).manifest().entries().get(0).modId());
    }

    @Test void symlinkJarCannotEnterManifestWhenSupported() throws Exception {
        Path outside = PhysicalJarFixture.mod(gameDir, "outside.jar", "outside", "*");
        Files.createDirectories(mods());
        try { Files.createSymbolicLink(mods().resolve("linked.jar"), outside); }
        catch (IOException | UnsupportedOperationException exception) {
            Assumptions.assumeTrue(false, "Symbolic links unavailable without elevated privileges");
        }
        assertThrows(IOException.class, () -> ServerDiscovery.discover(gameDir));
    }

    @Test void absentModsDirectoryProducesEmptyDiscovery() throws Exception {
        assertTrue(ServerDiscovery.discover(gameDir).mods().isEmpty());
    }
}
