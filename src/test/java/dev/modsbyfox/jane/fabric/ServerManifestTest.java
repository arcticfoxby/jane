package dev.modsbyfox.jane.fabric;

import static org.junit.jupiter.api.Assertions.*;

import dev.modsbyfox.jane.core.ClientSyncDecision;
import dev.modsbyfox.jane.core.Hashing;
import dev.modsbyfox.jane.core.RequiredManifest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerManifestTest {
    @TempDir Path gameDir;

    private Path mods() { return gameDir.resolve("mods"); }

    @Test void universalClientAndFabricApiEnterPhysicalProtocolThreeManifest() throws Exception {
        Path universal = PhysicalJarFixture.mod(mods(), "universal.jar", "universal", "*");
        Path client = PhysicalJarFixture.mod(mods(), "client.jar", "client", "client");
        Path fabricApi = PhysicalJarFixture.mod(mods(), "fabric-api.jar", "fabric-api", "*");
        PhysicalJarFixture.mod(mods(), "server.jar", "server", "server");
        PhysicalJarFixture.mod(mods(), "jane.jar", "jane", "*");

        ServerManifest.BuildResult result = ServerManifest.build(ServerDiscovery.discover(gameDir).mods(), gameDir);
        assertEquals(3, result.manifest().protocol());
        assertEquals(List.of("client", "fabric-api", "universal"), result.manifest().entries().stream()
                .map(entry -> entry.modId()).toList());
        for (Path jar : List.of(universal, client, fabricApi)) {
            String id = jar.getFileName().toString().replace(".jar", "");
            var entry = result.manifest().entries().stream().filter(item -> item.modId().equals(id)).findFirst().orElseThrow();
            assertEquals(Files.size(jar), entry.fileSize());
            assertEquals(Hashing.sha512(jar), entry.sha512());
        }
        Map<String, ClientSyncDecision> decisions = result.classified().stream().collect(Collectors.toMap(
                item -> item.discovered().candidate().modId(), ServerManifest.Classified::decision));
        assertEquals(ClientSyncDecision.EXCLUDE, decisions.get("server"));
        assertEquals(ClientSyncDecision.EXCLUDE, decisions.get("jane"));
        assertEquals(ClientSyncDecision.SYNC, decisions.get("client"));
    }

    @Test void serverOnlyAndJaneAreExcludedBeforeHashing() throws Exception {
        Path server = PhysicalJarFixture.mod(mods(), "server.jar", "server", "server");
        Path jane = PhysicalJarFixture.mod(mods(), "jane.jar", "jane", "*");
        List<ServerDiscovery.Discovered> discovered = ServerDiscovery.discover(gameDir).mods();
        Files.delete(server);
        Files.delete(jane);
        ServerManifest.BuildResult result = ServerManifest.build(discovered, gameDir);
        assertTrue(result.manifest().entries().isEmpty());
        assertNull(result.classified().get(0).jar());
        assertNull(result.classified().get(1).jar());
        assertEquals(Set.of("fabric_server_only", "jane_internal"), result.classified().stream()
                .map(ServerManifest.Classified::reason).collect(Collectors.toSet()));
    }

    @Test void physicalClientDependenciesRemainRequiredWithoutLoaderOrDependencyGraph() throws Exception {
        List<String> ids = List.of("accessories", "cloth-config", "ad_astra", "resourcefulconfig",
                "carpet-tis-addition", "carpet", "shape-shifter-curse", "satin", "tacztweaks",
                "fabric-language-kotlin", "yet-another-config-lib");
        for (int index = 0; index < ids.size(); index++) {
            String id = ids.get(index);
            PhysicalJarFixture.mod(mods(), id + ".jar", id, index % 2 == 0 ? "client" : "*");
        }
        ServerManifest.BuildResult result = ServerManifest.build(ServerDiscovery.discover(gameDir).mods(), gameDir);
        assertEquals(ids.size(), result.manifest().entries().size());
        assertEquals(Set.copyOf(ids), result.manifest().entries().stream().map(entry -> entry.modId())
                .collect(Collectors.toSet()));
        assertTrue(result.classified().stream().allMatch(item -> item.decision() == ClientSyncDecision.SYNC));
    }

    @Test void manifestLimitStillAppliesAfterPhysicalExclusions() throws Exception {
        for (int index = 0; index < RequiredManifest.MAX_ENTRIES; index++)
            PhysicalJarFixture.mod(mods(), "mod" + index + ".jar", "mod" + index, "*");
        PhysicalJarFixture.mod(mods(), "server.jar", "server", "server");
        PhysicalJarFixture.mod(mods(), "jane.jar", "jane", "*");
        assertEquals(RequiredManifest.MAX_ENTRIES,
                ServerManifest.build(ServerDiscovery.discover(gameDir).mods(), gameDir).manifest().entries().size());
        PhysicalJarFixture.mod(mods(), "extra.jar", "extra", "client");
        IOException error = assertThrows(IOException.class,
                () -> ServerManifest.build(ServerDiscovery.discover(gameDir).mods(), gameDir));
        assertTrue(error.getMessage().contains("more client-sync entries than Protocol 3 supports"));
    }
}
