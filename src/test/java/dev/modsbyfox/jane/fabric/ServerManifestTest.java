package dev.modsbyfox.jane.fabric;

import static org.junit.jupiter.api.Assertions.*;

import dev.modsbyfox.jane.core.RequiredEnvironment;
import dev.modsbyfox.jane.core.RequiredManifest;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.loader.api.metadata.ModEnvironment;
import org.junit.jupiter.api.Test;

class ServerManifestTest {
    private static String hash(char digit) { return Character.toString(digit).repeat(128); }

    private static ServerManifest.Candidate candidate(String id, ModEnvironment environment, String hash) {
        return new ServerManifest.Candidate(environment, "Name " + id, "1.2.3", () -> {
            if (environment != ModEnvironment.UNIVERSAL) fail("Fabric side-only mod must not be hashed");
            return new ServerManifest.JarData(1234, hash);
        });
    }

    @Test
    void filtersSideOnlyAndOptionalBeforeProtocolOneManifest() throws Exception {
        Map<String, ServerManifest.Candidate> candidates = Map.of(
                "server", candidate("server", ModEnvironment.SERVER, hash('a')),
                "client", candidate("client", ModEnvironment.CLIENT, hash('b')),
                "common", candidate("common", ModEnvironment.UNIVERSAL, hash('c')),
                "optional", candidate("optional", ModEnvironment.UNIVERSAL, hash('d')),
                "servermod", candidate("servermod", ModEnvironment.UNIVERSAL, hash('e')));
        AtomicInteger calls = new AtomicInteger();
        RequiredManifest manifest = ServerManifest.build(new ServerConfig.Config(
                        List.of("server", "client", "common", "optional", "servermod"), Map.of()), candidates::get, hashes -> {
                    calls.incrementAndGet();
                    assertEquals(Set.of(hash('c'), hash('d'), hash('e')), hashes);
                    return Map.of(hash('c'), "client_and_server", hash('d'), "server_only_client_optional",
                            hash('e'), "dedicated_server_only");
                });
        assertEquals(1, calls.get());
        assertEquals(RequiredManifest.PROTOCOL, manifest.protocol());
        assertEquals(List.of("common"), manifest.entries().stream().map(entry -> entry.modId()).toList());
        assertEquals(hash('c'), manifest.entries().get(0).sha512());
        assertEquals("1.2.3", manifest.entries().get(0).version());
    }

    @Test
    void fabricOnlyCandidatesDoNotCallModrinth() throws Exception {
        RequiredManifest manifest = ServerManifest.build(new ServerConfig.Config(List.of("server"), Map.of()),
                id -> candidate(id, ModEnvironment.SERVER, hash('a')),
                hashes -> { fail("No universal candidate; no HTTP lookup is needed"); return Map.of(); });
        assertTrue(manifest.entries().isEmpty());
    }

    @Test
    void unknownNeedsExplicitOverride() throws Exception {
        var candidate = candidate("private_mod", ModEnvironment.UNIVERSAL, hash('a'));
        assertThrows(IOException.class, () -> ServerManifest.build(new ServerConfig.Config(List.of("private_mod"), Map.of()),
                id -> candidate, hashes -> Map.of()));
        RequiredManifest manifest = ServerManifest.build(new ServerConfig.Config(List.of("private_mod"),
                        Map.of("private_mod", RequiredEnvironment.CLIENT_REQUIRED)), id -> candidate, hashes -> Map.of());
        assertEquals(List.of("private_mod"), manifest.entries().stream().map(entry -> entry.modId()).toList());
    }

    @Test
    void explicitClassificationsCannotBeOverridden() {
        for (ModEnvironment environment : new ModEnvironment[]{ModEnvironment.SERVER, ModEnvironment.CLIENT}) {
            assertThrows(IOException.class, () -> ServerManifest.build(new ServerConfig.Config(List.of("mod"),
                            Map.of("mod", RequiredEnvironment.CLIENT_REQUIRED)),
                    id -> candidate(id, environment, hash('a')), hashes -> Map.of()));
        }
        for (String modrinth : List.of("server_only", "client_only", "client_and_server")) {
            assertThrows(IOException.class, () -> ServerManifest.build(new ServerConfig.Config(List.of("mod"),
                            Map.of("mod", RequiredEnvironment.CLIENT_REQUIRED)),
                    id -> candidate(id, ModEnvironment.UNIVERSAL, hash('a')),
                    hashes -> Map.of(hash('a'), modrinth)), modrinth);
        }
    }

    @Test
    void batchFailureFailsWholeManifest() {
        IOException error = assertThrows(IOException.class, () -> ServerManifest.build(
                new ServerConfig.Config(List.of("mod"), Map.of()),
                id -> candidate(id, ModEnvironment.UNIVERSAL, hash('a')),
                hashes -> { throw new IOException("HTTP 503"); }));
        assertTrue(error.getMessage().contains("HTTP 503"));
    }
}
