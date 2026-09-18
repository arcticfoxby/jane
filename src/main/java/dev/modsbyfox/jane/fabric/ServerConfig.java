package dev.modsbyfox.jane.fabric;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class ServerConfig {
    record Config(boolean legacyFieldsPresent) { }

    private ServerConfig() { }

    static Config read(Path gameDir, Path configDir) throws IOException {
        Path realGame = gameDir.toRealPath();
        if (!configDir.toAbsolutePath().normalize().getParent().equals(realGame) || Files.isSymbolicLink(configDir)) {
            throw new IOException("Jane config directory is outside the current instance");
        }
        if (!Files.exists(configDir)) Files.createDirectory(configDir);
        if (!configDir.toRealPath().getParent().equals(realGame)) throw new IOException("Jane config directory escaped current instance");
        Path janeDir = configDir.resolve("jane");
        Path path = janeDir.resolve("server.json");
        if (Files.isSymbolicLink(janeDir) || Files.isSymbolicLink(path)) {
            throw new IOException("Jane server config path is a symlink");
        }
        if (!Files.exists(janeDir)) Files.createDirectory(janeDir);
        if (!janeDir.toRealPath().getParent().equals(configDir.toRealPath())) throw new IOException("Jane config path escaped current instance");
        if (!Files.exists(path)) Files.writeString(path, "{\n  \"mode\": \"AUTO_DISCOVER\"\n}\n", StandardCharsets.UTF_8);
        if (!Files.isRegularFile(path) || Files.size(path) > 64 * 1024) throw new IOException("Invalid Jane server config size or type");
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) throw new IOException("Invalid Jane server.json");
            JsonObject json = parsed.getAsJsonObject();
            for (String key : json.keySet()) {
                if (!"mode".equals(key) && !"requiredMods".equals(key) && !"environmentOverrides".equals(key)) {
                    throw new IOException("Unknown Jane server.json field: " + key);
                }
            }
            if (json.has("mode") && (!json.get("mode").isJsonPrimitive()
                    || !"AUTO_DISCOVER".equals(json.get("mode").getAsString()))) {
                throw new IOException("Jane server.json mode must be AUTO_DISCOVER");
            }
            boolean legacy = json.has("requiredMods") || json.has("environmentOverrides");
            if (!json.has("mode") && !legacy) throw new IOException("Jane server.json requires AUTO_DISCOVER mode");
            return new Config(legacy);
        } catch (RuntimeException exception) {
            throw new IOException("Invalid Jane server.json", exception);
        }
    }
}
