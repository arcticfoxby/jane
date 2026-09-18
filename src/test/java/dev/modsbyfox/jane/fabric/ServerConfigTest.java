package dev.modsbyfox.jane.fabric;

import static org.junit.jupiter.api.Assertions.*;

import dev.modsbyfox.jane.core.RequiredEnvironment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerConfigTest {
    @TempDir Path gameDir;

    private Path file() { return gameDir.resolve("config/jane/server.json"); }

    @Test
    void createsNewDefaultAndReadsOldAndNewFormats() throws Exception {
        Path configDir = gameDir.resolve("config");
        assertEquals(List.of(), ServerConfig.read(gameDir, configDir).requiredMods());
        assertEquals(Map.of(), ServerConfig.read(gameDir, configDir).environmentOverrides());
        assertTrue(Files.readString(file()).contains("\"environmentOverrides\": {}"));

        Files.writeString(file(), "{\"requiredMods\":[\"create\"]}");
        assertEquals(List.of("create"), ServerConfig.read(gameDir, configDir).requiredMods());
        assertEquals(Map.of(), ServerConfig.read(gameDir, configDir).environmentOverrides());

        Files.writeString(file(), "{\"requiredMods\":[\"create\"],\"environmentOverrides\":{}}");
        assertEquals(List.of("create"), ServerConfig.read(gameDir, configDir).requiredMods());

        Files.writeString(file(), "{\"requiredMods\":[\"private_mod\"],"
                + "\"environmentOverrides\":{\"private_mod\":\"CLIENT_REQUIRED\"}}");
        assertEquals(Map.of("private_mod", RequiredEnvironment.CLIENT_REQUIRED),
                ServerConfig.read(gameDir, configDir).environmentOverrides());
    }

    @Test
    void rejectsBadRequiredIdsAndOverrides() throws Exception {
        Path configDir = gameDir.resolve("config");
        ServerConfig.read(gameDir, configDir);
        for (String invalid : List.of(
                "{\"requiredMods\":[\"create\",\"create\"]}",
                "{\"requiredMods\":[\"../outside\"]}",
                "{\"requiredMods\":[\"jane\"]}",
                "{\"requiredMods\":[\"create\"],\"environmentOverrides\":{\"other\":\"CLIENT_REQUIRED\"}}",
                "{\"requiredMods\":[\"create\"],\"environmentOverrides\":{\"../bad\":\"CLIENT_REQUIRED\"}}",
                "{\"requiredMods\":[\"create\"],\"environmentOverrides\":{\"create\":\"SERVER_ONLY\"}}",
                "{\"requiredMods\":[\"create\"],\"environmentOverrides\":{\"create\":\"CLIENT_OPTIONAL\"}}",
                "{\"requiredMods\":[\"create\"],\"environmentOverrides\":{\"create\":\"UNKNOWN\"}}",
                "{\"requiredMods\":[\"create\"],\"environmentOverrides\":{\"create\":\"REQUIERD\"}}",
                "{\"requiredMods\":[\"create\"],\"environmentOverride\":{\"create\":\"CLIENT_REQUIRED\"}}")) {
            Files.writeString(file(), invalid);
            assertThrows(IOException.class, () -> ServerConfig.read(gameDir, configDir), invalid);
        }
    }
}
