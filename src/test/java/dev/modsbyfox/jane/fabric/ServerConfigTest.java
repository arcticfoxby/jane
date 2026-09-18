package dev.modsbyfox.jane.fabric;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerConfigTest {
    @TempDir Path gameDir;

    private Path file() { return gameDir.resolve("config/jane/server.json"); }

    @Test
    void createsAutoDiscoverDefaultAndAcceptsLegacyFieldsWithoutUsingThem() throws Exception {
        Path configDir = gameDir.resolve("config");
        assertFalse(ServerConfig.read(gameDir, configDir).legacyFieldsPresent());
        assertFalse(ServerConfig.read(gameDir, configDir).provider().enabled());
        assertTrue(Files.readString(file()).contains("\"mode\": \"AUTO_DISCOVER\""));
        Files.writeString(file(), "{\"requiredMods\":[\"old_mod\"],\"environmentOverrides\":{\"old_mod\":\"CLIENT_REQUIRED\"}}");
        assertTrue(ServerConfig.read(gameDir, configDir).legacyFieldsPresent());
        assertTrue(Files.readString(file()).contains("old_mod"));
    }

    @Test
    void providerPortsAreValidatedAndHostFieldsAreForbidden() throws Exception {
        Path configDir = gameDir.resolve("config");
        ServerConfig.read(gameDir, configDir);
        Files.writeString(file(), "{\"mode\":\"AUTO_DISCOVER\",\"serverProvider\":{\"enabled\":true,\"bindPort\":25566,\"advertisedPort\":41477}}");
        assertEquals(41477, ServerConfig.read(gameDir, configDir).provider().advertisedPort());
        for (String invalid : new String[]{
                "{\"mode\":\"AUTO_DISCOVER\",\"serverProvider\":{\"enabled\":true,\"bindPort\":0,\"advertisedPort\":2}}",
                "{\"mode\":\"AUTO_DISCOVER\",\"serverProvider\":{\"enabled\":true,\"bindPort\":2,\"advertisedPort\":65536}}",
                "{\"mode\":\"AUTO_DISCOVER\",\"serverProvider\":{\"enabled\":\"true\",\"bindPort\":2,\"advertisedPort\":3}}",
                "{\"mode\":\"AUTO_DISCOVER\",\"serverProvider\":{\"enabled\":true,\"bindPort\":2,\"advertisedPort\":3,\"host\":\"evil.example\"}}"}) {
            Files.writeString(file(), invalid);
            assertThrows(IOException.class, () -> ServerConfig.read(gameDir, configDir));
        }
    }

    @Test
    void rejectsUnsupportedModeUnknownFieldsAndOversize() throws Exception {
        Path configDir = gameDir.resolve("config");
        ServerConfig.read(gameDir, configDir);
        for (String invalid : new String[]{"{\"mode\":\"MANUAL\"}", "{\"untrustedUrl\":\"https://invalid\"}", "[]", "{}"}) {
            Files.writeString(file(), invalid);
            assertThrows(IOException.class, () -> ServerConfig.read(gameDir, configDir));
        }
        Files.writeString(file(), " ".repeat(64 * 1024 + 1));
        assertThrows(IOException.class, () -> ServerConfig.read(gameDir, configDir));
    }
}
