package dev.modsbyfox.jane.fabric;

import static org.junit.jupiter.api.Assertions.*;

import dev.modsbyfox.jane.core.ClientSyncDecision;
import dev.modsbyfox.jane.core.Hashing;
import dev.modsbyfox.jane.core.RequiredManifest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.fabricmc.loader.api.metadata.ModEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerManifestTest {
    @TempDir Path gameDir;

    private ServerDiscovery.Discovered item(String id, ModEnvironment environment) throws IOException {
        Path jar = Files.write(gameDir.resolve(id + ".jar"), id.getBytes(StandardCharsets.UTF_8));
        return new ServerDiscovery.Discovered(new ServerDiscovery.Candidate(id, "Name " + id, "1.2.3",
                environment, false, List.of(jar)), jar);
    }

    @Test
    void explicitExclusionsAndUnknownsBuildProtocolOneManifest() throws Exception {
        var server = item("server", ModEnvironment.SERVER);
        var client = item("client", ModEnvironment.CLIENT);
        var common = item("common", ModEnvironment.UNIVERSAL);
        var optional = item("optional", ModEnvironment.UNIVERSAL);
        var absent = item("absent", ModEnvironment.UNIVERSAL);
        var future = item("future", ModEnvironment.UNIVERSAL);
        String commonHash = Hashing.sha512(common.jar());
        String optionalHash = Hashing.sha512(optional.jar());
        String futureHash = Hashing.sha512(future.jar());
        ServerManifest.BuildResult result = ServerManifest.build(List.of(server, client, common, optional, absent, future),
                hashes -> {
                    assertEquals(4, hashes.size());
                    return Map.of(commonHash, "client_and_server", optionalHash, "server_only_client_optional",
                            futureHash, "future_environment");
                });
        assertEquals(RequiredManifest.PROTOCOL, result.manifest().protocol());
        assertEquals(List.of("common", "absent", "future"),
                result.manifest().entries().stream().map(entry -> entry.modId()).toList());
        Map<String, ClientSyncDecision> decisions = result.classified().stream().collect(Collectors.toMap(
                classified -> classified.discovered().candidate().modId(), ServerManifest.Classified::decision));
        assertEquals(ClientSyncDecision.SYNC, decisions.get("common"));
        assertEquals(ClientSyncDecision.SYNC_CONSERVATIVE, decisions.get("absent"));
        assertEquals(ClientSyncDecision.SYNC_CONSERVATIVE, decisions.get("future"));
        assertEquals(ClientSyncDecision.EXCLUDE, decisions.get("server"));
        assertEquals(ClientSyncDecision.EXCLUDE, decisions.get("optional"));
    }

    @Test
    void fabricServerOnlySkipsHashingAndLookup() throws Exception {
        var missing = new ServerDiscovery.Discovered(new ServerDiscovery.Candidate("server", "Server", "1",
                ModEnvironment.SERVER, false, List.of(gameDir.resolve("missing.jar"))), gameDir.resolve("missing.jar"));
        var result = ServerManifest.build(List.of(missing), hashes -> {
            fail("No lookup for Fabric SERVER"); return Map.of();
        });
        assertTrue(result.manifest().entries().isEmpty());
        assertNull(result.classified().get(0).jar());
    }

    @Test
    void infrastructureFailureFailsWholeManifest() throws Exception {
        var mod = item("mod", ModEnvironment.UNIVERSAL);
        IOException error = assertThrows(IOException.class, () -> ServerManifest.build(List.of(mod),
                hashes -> { throw new IOException("HTTP 503"); }));
        assertTrue(error.getMessage().contains("HTTP 503"));
    }

    @Test
    void finalLimitAppliesAfterFiltering() throws Exception {
        List<ServerDiscovery.Discovered> items = new ArrayList<>();
        for (int i = 0; i < 129; i++) items.add(item("mod" + i, ModEnvironment.UNIVERSAL));
        String excluded = Hashing.sha512(items.get(0).jar());
        assertEquals(128, ServerManifest.build(items, hashes -> Map.of(excluded, "server_only"))
                .manifest().entries().size());
        IOException error = assertThrows(IOException.class, () -> ServerManifest.build(items, hashes -> Map.of()));
        assertTrue(error.getMessage().contains("more client-sync entries than Protocol 3 supports"));
    }
}
