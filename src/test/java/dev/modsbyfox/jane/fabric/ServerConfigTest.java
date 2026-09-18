package dev.modsbyfox.jane.fabric;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerConfigTest {
    @TempDir Path gameDir;

    @Test
    void createsEmptyDefaultAndReadsRequiredIds() throws Exception {
        Path configDir = gameDir.resolve("config");
        assertEquals(List.of(), ServerConfig.read(gameDir, configDir));
        Path file = configDir.resolve("jane/server.json");
        assertTrue(Files.isRegularFile(file));
        Files.writeString(file, "{\"requiredMods\":[\"create\",\"farmersdelight\"]}");
        assertEquals(List.of("create", "farmersdelight"), ServerConfig.read(gameDir, configDir));
    }

    @Test
    void invalidRequiredIdsInvalidateTheConfig() throws Exception {
        Path configDir = gameDir.resolve("config");
        ServerConfig.read(gameDir, configDir);
        Path file = configDir.resolve("jane/server.json");
        Files.writeString(file, "{\"requiredMods\":[\"create\",\"create\"]}");
        assertThrows(java.io.IOException.class, () -> ServerConfig.read(gameDir, configDir));
        Files.writeString(file, "{\"requiredMods\":[\"../outside\"]}");
        assertThrows(java.io.IOException.class, () -> ServerConfig.read(gameDir, configDir));
    }
}
