package dev.modsbyfox.jane.fabric;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import dev.modsbyfox.jane.core.ClientSyncDecision;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.fabricmc.loader.api.metadata.ModEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiscoveredModsStoreTest {
    @TempDir Path gameDir;

    @Test
    void writesOnlyRelativeDiagnosticsAndNeverReadsOldOutput() throws Exception {
        Path jar = gameDir.resolve("example.jar");
        var candidate = new ServerDiscovery.Candidate("example", "Example", "1.0", ModEnvironment.UNIVERSAL,
                false, List.of(jar));
        var item = new ServerManifest.Classified(new ServerDiscovery.Discovered(candidate, jar),
                new ServerManifest.JarData(7, "a".repeat(128)), null, ClientSyncDecision.SYNC_CONSERVATIVE,
                "modrinth_hash_absent");
        DiscoveredModsStore.write(gameDir, List.of(item));
        Path diagnostic = gameDir.resolve("config/jane/discovered-mods.json");
        String content = Files.readString(diagnostic);
        assertFalse(content.contains(gameDir.toString()));
        var json = JsonParser.parseString(content).getAsJsonObject();
        assertEquals(1, json.get("schemaVersion").getAsInt());
        assertEquals("1.0.4", json.get("janeVersion").getAsString());
        assertEquals("SYNC_CONSERVATIVE", json.getAsJsonArray("mods").get(0).getAsJsonObject()
                .get("syncDecision").getAsString());
        var mod = json.getAsJsonArray("mods").get(0).getAsJsonObject();
        assertEquals("example.jar", mod.get("fileName").getAsString());
        assertEquals(7, mod.get("fileSize").getAsLong());
        assertEquals("UNIVERSAL", mod.get("fabricEnvironment").getAsString());
        assertFalse(mod.get("modrinthExactMatch").getAsBoolean());
        Files.writeString(diagnostic, "untrusted prior output");
        DiscoveredModsStore.write(gameDir, List.of(item));
        assertFalse(Files.readString(diagnostic).contains("untrusted"));
    }

    @Test
    void refusesUnsafeDiagnosticDirectory() throws Exception {
        Files.writeString(gameDir.resolve("config"), "file");
        assertThrows(IOException.class, () -> DiscoveredModsStore.write(gameDir, List.of()));
    }

    @Test
    void nestedModAndTamperedDiagnosticNeverChangeDiscoveryInput() throws Exception {
        Path mods = Files.createDirectory(gameDir.resolve("mods"));
        Path jar = Files.write(mods.resolve("parent.jar"), new byte[]{1, 2, 3});
        var parent = new ServerDiscovery.Candidate("parent", "Parent", "1.0", ModEnvironment.UNIVERSAL,
                false, List.of(jar));
        var nested = new ServerDiscovery.Candidate("nested", "Nested", "1.0", ModEnvironment.UNIVERSAL,
                true, List.of(jar));
        var discovered = ServerDiscovery.discover(List.of(parent, nested), gameDir);
        var result = ServerManifest.build(discovered, hashes -> java.util.Map.of());
        DiscoveredModsStore.write(gameDir, result.classified());
        Path diagnostic = gameDir.resolve("config/jane/discovered-mods.json");
        assertEquals(1, JsonParser.parseString(Files.readString(diagnostic)).getAsJsonObject()
                .getAsJsonArray("mods").size());
        Files.writeString(diagnostic, "{\"mods\":[]}");
        assertEquals(List.of("parent"), ServerManifest.build(ServerDiscovery.discover(List.of(parent, nested), gameDir),
                hashes -> java.util.Map.of()).manifest().entries().stream().map(entry -> entry.modId()).toList());
    }
}
