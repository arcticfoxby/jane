package dev.modsbyfox.jane.fabric;

import static org.junit.jupiter.api.Assertions.*;

import dev.modsbyfox.jane.core.ClientSyncDecision;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import net.fabricmc.loader.api.metadata.ModEnvironment;
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
        IOException error = assertThrows(IOException.class,
                () -> ServerDiscovery.discover(gameDir, id -> Optional.empty()));
        assertTrue(error.getMessage().contains("highest SemanticVersion is not unique"));
        assertTrue(error.getMessage().contains("one.jar version 1.2.3"));
        assertTrue(error.getMessage().contains("two.jar version 1.2.3"));
    }

    @Test void farmersDelightUsesLoaderSelectedPhysicalJarAndManifestHasOneEntry() throws Exception {
        Path old = PhysicalJarFixture.mod(mods(), "farmersdelight-old.jar", "farmersdelight",
                "1.20.1-2.4.0+refabricated", "*");
        Path newer = PhysicalJarFixture.mod(mods(), "farmersdelight-new.jar", "farmersdelight",
                "1.20.1-2.5.4+refabricated", "*");
        ServerDiscovery.Discovery discovery = ServerDiscovery.discover(gameDir,
                id -> Optional.of(newer));
        assertEquals(List.of(newer), discovery.mods().stream().map(ServerDiscovery.Discovered::jar).toList());
        assertEquals(List.of(old), discovery.skipped().stream().map(ServerDiscovery.Skipped::jar).toList());
        assertEquals("duplicate_not_selected_by_loader", discovery.skipped().get(0).reason());
        assertEquals("farmersdelight", discovery.skipped().get(0).candidate().modId());
        assertEquals(List.of("farmersdelight"), ServerManifest.build(discovery.mods(), gameDir).manifest()
                .entries().stream().map(entry -> entry.modId()).toList());
    }

    @Test void loaderSelectionWinsOverNewerVersion() throws Exception {
        Path selected = PhysicalJarFixture.mod(mods(), "abc-2.0.0.jar", "abc", "2.0.0", "*");
        Path newer = PhysicalJarFixture.mod(mods(), "abc-3.0.0.jar", "abc", "3.0.0", "*");
        ServerDiscovery.Discovery discovery = ServerDiscovery.discover(gameDir, id -> Optional.of(selected));
        assertEquals(selected, discovery.mods().get(0).jar());
        assertEquals(newer, discovery.skipped().get(0).jar());
        assertEquals("duplicate_not_selected_by_loader", discovery.skipped().get(0).reason());
    }

    @Test void clientOnlyDuplicatesChooseUniqueNewestSemanticVersion() throws Exception {
        Path old = PhysicalJarFixture.mod(mods(), "clientlib-1.2.0.jar", "clientlib", "1.2.0", "client");
        Path newer = PhysicalJarFixture.mod(mods(), "clientlib-1.4.0.jar", "clientlib", "1.4.0", "client");
        ServerDiscovery.Discovery discovery = ServerDiscovery.discover(gameDir, id -> Optional.empty());
        assertEquals(newer, discovery.mods().get(0).jar());
        assertEquals(old, discovery.skipped().get(0).jar());
        assertEquals("duplicate_older_semver", discovery.skipped().get(0).reason());
        assertEquals("1.4.0", ServerManifest.build(discovery.mods(), gameDir).manifest().entries().get(0).version());
    }

    @Test void nonComparableVersionsFailClosedWithStableCandidateDetails() throws Exception {
        PhysicalJarFixture.mod(mods(), "weird-0.4.0a.jar", "weird", "0.4.0a", "client");
        PhysicalJarFixture.mod(mods(), "weird-0.5.0a.jar", "weird", "0.5.0a", "client");
        IOException error = assertThrows(IOException.class,
                () -> ServerDiscovery.discover(gameDir, id -> Optional.empty()));
        assertTrue(error.getMessage().contains("weird"));
        assertTrue(error.getMessage().contains("weird-0.4.0a.jar version 0.4.0a"));
        assertTrue(error.getMessage().contains("weird-0.5.0a.jar version 0.5.0a"));
    }

    @Test void equalSemanticPrecedenceFailsClosed() throws Exception {
        PhysicalJarFixture.mod(mods(), "same-a.jar", "same", "1.2.0+build1", "client");
        PhysicalJarFixture.mod(mods(), "same-b.jar", "same", "1.2.0+build2", "client");
        IOException error = assertThrows(IOException.class,
                () -> ServerDiscovery.discover(gameDir, id -> Optional.empty()));
        assertTrue(error.getMessage().contains("highest SemanticVersion is not unique"));
        assertTrue(error.getMessage().contains("same-a.jar version 1.2.0+build1"));
        assertTrue(error.getMessage().contains("same-b.jar version 1.2.0+build2"));
    }

    @Test void unmappableLoaderOriginFailsClosedWithoutVersionFallback() throws Exception {
        PhysicalJarFixture.mod(mods(), "abc-1.0.0.jar", "abc", "1.0.0", "client");
        PhysicalJarFixture.mod(mods(), "abc-2.0.0.jar", "abc", "2.0.0", "client");
        Path other = PhysicalJarFixture.mod(mods(), "other.jar", "other", "1.0.0", "client");
        IOException unmatched = assertThrows(IOException.class,
                () -> ServerDiscovery.discover(gameDir, id -> Optional.of(other)));
        assertTrue(unmatched.getMessage().contains("not a physical candidate"));
        assertTrue(unmatched.getMessage().contains("abc-1.0.0.jar version 1.0.0"));
        Path outside = PhysicalJarFixture.mod(gameDir, "outside.jar", "outside", "1.0.0", "client");
        IOException unsafe = assertThrows(IOException.class,
                () -> ServerDiscovery.discover(gameDir, id -> Optional.of(outside)));
        assertTrue(unsafe.getMessage().contains("selected origin is unsafe"));
        IOException invalid = assertThrows(IOException.class,
                () -> ServerDiscovery.discover(gameDir, id -> { throw new IOException("nested origin"); }));
        assertTrue(invalid.getMessage().contains("selected origin unavailable"));
    }

    @Test void schemaZeroAndMissingSchemaUseSideAndUnknownSchemaFailsClosed() throws Exception {
        Path noSchema = PhysicalJarFixture.jar(mods(), "legacy.jar",
                "{\"id\":\"legacy\",\"version\":\"1.0\",\"side\":\"universal\"}");
        Path client = PhysicalJarFixture.jar(mods(), "client.jar",
                "{\"schemaVersion\":0,\"id\":\"client\",\"version\":\"1.0\",\"side\":\"client\"}");
        Path server = PhysicalJarFixture.jar(mods(), "server.jar",
                "{\"schemaVersion\":0,\"id\":\"server\",\"version\":\"1.0\",\"side\":\"server\"}");
        Path defaultSide = PhysicalJarFixture.jar(mods(), "default.jar",
                "{\"schemaVersion\":0,\"id\":\"default\",\"version\":\"1.0\"}");
        ServerDiscovery.Discovery discovery = ServerDiscovery.discover(gameDir);
        Map<Path, ModEnvironment> sides = discovery.mods().stream().collect(Collectors.toMap(
                ServerDiscovery.Discovered::jar, item -> item.candidate().fabricEnvironment()));
        assertEquals(ModEnvironment.UNIVERSAL, sides.get(noSchema));
        assertEquals(ModEnvironment.CLIENT, sides.get(client));
        assertEquals(ModEnvironment.SERVER, sides.get(server));
        assertEquals(ModEnvironment.UNIVERSAL, sides.get(defaultSide));
        assertEquals(List.of("client", "default", "legacy"),
                ServerManifest.build(discovery.mods(), gameDir).manifest().entries().stream()
                        .map(entry -> entry.modId()).toList());
        PhysicalJarFixture.jar(mods(), "future.jar",
                "{\"schemaVersion\":2,\"id\":\"future\",\"version\":\"1.0\"}");
        assertThrows(IOException.class, () -> ServerDiscovery.discover(gameDir));
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
