package dev.modsbyfox.jane.fabric;

import static org.junit.jupiter.api.Assertions.*;

import dev.modsbyfox.jane.core.ClientSyncDecision;
import dev.modsbyfox.jane.core.Hashing;
import dev.modsbyfox.jane.core.RequiredManifest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerManifestTest {
    @TempDir Path gameDir;

    private Path mods() { return gameDir.resolve("mods"); }
    private Path blacklist() { return gameDir.resolve("jane/blacklist"); }

    @Test void identicalBlacklistCopyExcludesOnlyThatJar() throws Exception {
        Path blocked = PhysicalJarFixture.mod(mods(), "blocked.jar", "blocked", "*");
        PhysicalJarFixture.mod(mods(), "kept.jar", "kept", "*");
        Files.createDirectories(blacklist());
        Files.copy(blocked, blacklist().resolve(blocked.getFileName()));

        ServerManifest.BuildResult result = ServerManifest.build(ServerDiscovery.discover(gameDir).mods(), gameDir);
        assertEquals(List.of("kept"), result.manifest().entries().stream().map(entry -> entry.modId()).toList());
        Map<String, ClientSyncDecision> decisions = result.classified().stream().collect(Collectors.toMap(
                item -> item.discovered().candidate().modId(), ServerManifest.Classified::decision));
        assertEquals(ClientSyncDecision.EXCLUDE, decisions.get("blocked"));
        assertEquals(ClientSyncDecision.SYNC, decisions.get("kept"));
        assertEquals("blacklist_exact_copy", result.classified().stream()
                .filter(item -> item.discovered().candidate().modId().equals("blocked"))
                .findFirst().orElseThrow().reason());
    }

    @Test void blacklistOnlyJarDoesNotAffectMods() throws Exception {
        Path kept = PhysicalJarFixture.mod(mods(), "kept.jar", "kept", "*");
        PhysicalJarFixture.mod(blacklist(), "orphan.jar", "orphan", "*");

        ServerManifest.BuildResult result = ServerManifest.build(ServerDiscovery.discover(gameDir).mods(), gameDir);
        assertEquals(List.of("kept"), result.manifest().entries().stream().map(entry -> entry.modId()).toList());
        assertEquals(Hashing.sha512(kept), result.manifest().entries().get(0).sha512());
        assertEquals(List.of(ClientSyncDecision.SYNC), result.classified().stream()
                .map(ServerManifest.Classified::decision).toList());
    }

    @Test void staleSameNameBlacklistCopyDoesNotExcludeChangedMod() throws Exception {
        Path current = PhysicalJarFixture.mod(mods(), "same.jar", "example", "2.0", "*");
        PhysicalJarFixture.mod(blacklist(), "same.jar", "example", "1.0", "*");
        assertEquals(Files.size(current), Files.size(blacklist().resolve("same.jar")));
        assertNotEquals(Hashing.sha512(current), Hashing.sha512(blacklist().resolve("same.jar")));

        ServerManifest.BuildResult result = ServerManifest.build(ServerDiscovery.discover(gameDir).mods(), gameDir);
        assertEquals(List.of("example"), result.manifest().entries().stream().map(entry -> entry.modId()).toList());
        assertEquals(Hashing.sha512(current), result.manifest().entries().get(0).sha512());
        assertEquals(ClientSyncDecision.SYNC, result.classified().get(0).decision());
    }

    @Test void buildCreatesBlacklistDirectory() throws Exception {
        assertFalse(Files.exists(blacklist(), LinkOption.NOFOLLOW_LINKS));
        ServerManifest.build(List.of(), gameDir);
        assertTrue(Files.isDirectory(blacklist(), LinkOption.NOFOLLOW_LINKS));
    }

    @Test void blacklistDirectorySymlinkIsRejectedWhenSupported() throws Exception {
        Files.createDirectory(gameDir.resolve("jane"));
        Path target = Files.createDirectory(gameDir.resolve("other"));
        try {
            Files.createSymbolicLink(blacklist(), target);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            Assumptions.assumeTrue(false, "Directory symlinks are unavailable in this test environment");
        }
        assertThrows(IOException.class, () -> ServerManifest.build(List.of(), gameDir));
    }

    @Test void universalClientAndFabricApiEnterPhysicalManifest() throws Exception {
        Path universal = PhysicalJarFixture.mod(mods(), "universal.jar", "universal", "*");
        Path client = PhysicalJarFixture.mod(mods(), "client.jar", "client", "client");
        Path fabricApi = PhysicalJarFixture.mod(mods(), "fabric-api.jar", "fabric-api", "*");
        PhysicalJarFixture.mod(mods(), "server.jar", "server", "server");
        PhysicalJarFixture.mod(mods(), "jane.jar", "jane", "*");

        ServerManifest.BuildResult result = ServerManifest.build(ServerDiscovery.discover(gameDir).mods(), gameDir);
        assertEquals(RequiredManifest.PROTOCOL, result.manifest().protocol());
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
        assertTrue(error.getMessage().contains("more client-sync entries than Protocol "
                + RequiredManifest.PROTOCOL + " supports"));
    }
}
