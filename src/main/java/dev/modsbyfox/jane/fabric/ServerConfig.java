package dev.modsbyfox.jane.fabric;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class ServerConfig {
    record Provider(boolean enabled, int bindPort, int advertisedPort) { }
    record Config(boolean legacyFieldsPresent, Provider provider) { }

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
        if (!Files.exists(path)) Files.writeString(path, "{\n  \"mode\": \"AUTO_DISCOVER\",\n  \"serverProvider\": {\n    \"enabled\": false,\n    \"bindPort\": 25566,\n    \"advertisedPort\": 25566\n  }\n}\n", StandardCharsets.UTF_8);
        if (!Files.isRegularFile(path) || Files.size(path) > 64 * 1024) throw new IOException("Invalid Jane server config size or type");
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) throw new IOException("Invalid Jane server.json");
            JsonObject json = parsed.getAsJsonObject();
            for (String key : json.keySet()) {
                if (!"mode".equals(key) && !"requiredMods".equals(key) && !"environmentOverrides".equals(key)
                        && !"serverProvider".equals(key)) {
                    throw new IOException("Unknown Jane server.json field: " + key);
                }
            }
            if (json.has("mode") && (!json.get("mode").isJsonPrimitive()
                    || !"AUTO_DISCOVER".equals(json.get("mode").getAsString()))) {
                throw new IOException("Jane server.json mode must be AUTO_DISCOVER");
            }
            boolean legacy = json.has("requiredMods") || json.has("environmentOverrides");
            if (!json.has("mode") && !legacy) throw new IOException("Jane server.json requires AUTO_DISCOVER mode");
            Provider provider = new Provider(false, 25566, 25566);
            if (json.has("serverProvider")) {
                if (!json.get("serverProvider").isJsonObject()) throw new IOException("Invalid ServerProvider config");
                JsonObject settings = json.getAsJsonObject("serverProvider");
                for (String key : settings.keySet()) {
                    if (!"enabled".equals(key) && !"bindPort".equals(key) && !"advertisedPort".equals(key)) {
                        throw new IOException("Unknown ServerProvider field: " + key);
                    }
                }
                if (!settings.has("enabled") || !settings.get("enabled").isJsonPrimitive()
                        || !settings.getAsJsonPrimitive("enabled").isBoolean()) throw new IOException("Invalid ServerProvider enabled");
                int bind = port(settings, "bindPort");
                int advertised = port(settings, "advertisedPort");
                provider = new Provider(settings.get("enabled").getAsBoolean(), bind, advertised);
            }
            return new Config(legacy, provider);
        } catch (RuntimeException exception) {
            throw new IOException("Invalid Jane server.json", exception);
        }
    }

    private static int port(JsonObject json, String key) throws IOException {
        if (!json.has(key) || !json.get(key).isJsonPrimitive()
                || !json.getAsJsonPrimitive(key).isNumber()
                || !json.get(key).getAsString().matches("[0-9]{1,5}")) throw new IOException("Invalid ServerProvider " + key);
        int value = json.get(key).getAsInt();
        if (value < 1 || value > 65535) throw new IOException("Invalid ServerProvider " + key);
        return value;
    }
}
