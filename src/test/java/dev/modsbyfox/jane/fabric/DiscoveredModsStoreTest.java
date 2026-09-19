package dev.modsbyfox.jane.fabric;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiscoveredModsStoreTest {
    @TempDir Path gameDir;

    @Test void writesPhysicalDecisionsWithoutPathsOrModrinthClassification() throws Exception {
        Path mods = gameDir.resolve("mods");
        Path jar = PhysicalJarFixture.mod(mods, "example.jar", "example", "client");
        PhysicalJarFixture.jar(mods, "library.jar", null);
        ServerDiscovery.Discovery discovery = ServerDiscovery.discover(gameDir);
        ServerManifest.BuildResult result = ServerManifest.build(discovery.mods(), gameDir);
        DiscoveredModsStore.write(gameDir, result.classified(), discovery.skipped());
        Path diagnostic = gameDir.resolve("config/jane/discovered-mods.json");
        String content = Files.readString(diagnostic);
        assertFalse(content.contains(gameDir.toString()));
        JsonObject json = JsonParser.parseString(content).getAsJsonObject();
        assertEquals(2, json.get("schemaVersion").getAsInt());
        assertEquals("1.1.0-beta.1", json.get("janeVersion").getAsString());
        assertEquals(2, json.getAsJsonArray("mods").size());
        JsonObject mod = json.getAsJsonArray("mods").get(0).getAsJsonObject();
        assertEquals("example", mod.get("modId").getAsString());
        assertEquals("CLIENT", mod.get("fabricEnvironment").getAsString());
        assertEquals("SYNC", mod.get("syncDecision").getAsString());
        assertEquals("physical_client_target", mod.get("reason").getAsString());
        assertEquals(Files.size(jar), mod.get("fileSize").getAsLong());
        assertEquals(128, mod.get("sha512").getAsString().length());
        assertFalse(mod.has("modrinthEnvironment"));
        assertFalse(mod.has("modrinthExactMatch"));
        JsonObject skipped = json.getAsJsonArray("mods").get(1).getAsJsonObject();
        assertEquals("library.jar", skipped.get("fileName").getAsString());
        assertEquals("SKIP", skipped.get("syncDecision").getAsString());
        assertEquals("not_fabric_mod", skipped.get("reason").getAsString());
    }

    @Test void diagnosticIsWriteOnlyAndCannotChangePhysicalDiscovery() throws Exception {
        PhysicalJarFixture.mod(gameDir.resolve("mods"), "parent.jar", "parent", "*");
        ServerDiscovery.Discovery discovery = ServerDiscovery.discover(gameDir);
        DiscoveredModsStore.write(gameDir, ServerManifest.build(discovery.mods(), gameDir).classified(),
                discovery.skipped());
        Path diagnostic = gameDir.resolve("config/jane/discovered-mods.json");
        Files.writeString(diagnostic, "{\"mods\":[]}");
        assertEquals(List.of("parent"), ServerManifest.build(ServerDiscovery.discover(gameDir).mods(), gameDir)
                .manifest().entries().stream().map(entry -> entry.modId()).toList());
    }

    @Test void refusesUnsafeDiagnosticDirectory() throws Exception {
        Files.writeString(gameDir.resolve("config"), "file");
        assertThrows(IOException.class, () -> DiscoveredModsStore.write(gameDir, List.of(), List.of()));
    }
}
