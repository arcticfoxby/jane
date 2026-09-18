package dev.modsbyfox.jane.fabric;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.modsbyfox.jane.core.ManifestEntry;
import dev.modsbyfox.jane.core.RequiredManifest;
import dev.modsbyfox.jane.core.RequiredEnvironment;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

final class ServerConfig {
    record Config(List<String> requiredMods, Map<String, RequiredEnvironment> environmentOverrides) {
        Config {
            requiredMods = List.copyOf(requiredMods);
            environmentOverrides = Map.copyOf(environmentOverrides);
        }
    }

    private ServerConfig() { }

    static Config read(Path gameDir, Path configDir) throws IOException {
        Path realGame = gameDir.toRealPath();
        if (!configDir.toAbsolutePath().normalize().getParent().equals(realGame) || Files.isSymbolicLink(configDir)) {
            throw new IOException("Jane config directory is outside the current instance");
        }
        if (!Files.exists(configDir)) Files.createDirectory(configDir);
        if (!configDir.toRealPath().getParent().equals(realGame)) throw new IOException("Jane config directory escaped current instance");
        Path path = configDir.resolve("jane/server.json");
        if (Files.isSymbolicLink(configDir) || Files.isSymbolicLink(path.getParent()) || Files.isSymbolicLink(path)) {
            throw new IOException("Jane server config path is a symlink");
        }
        if (!Files.exists(path)) {
            Files.createDirectories(path.getParent());
            Files.writeString(path, "{\n  \"requiredMods\": [],\n  \"environmentOverrides\": {}\n}\n", StandardCharsets.UTF_8);
        }
        if (Files.size(path) > 64 * 1024) throw new IOException("Jane server config is too large");
        try {
            JsonObject json = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            for (String key : json.keySet()) {
                if (!"requiredMods".equals(key) && !"environmentOverrides".equals(key)) {
                    throw new IOException("Unknown Jane server.json field: " + key);
                }
            }
            JsonArray array = json.getAsJsonArray("requiredMods");
            if (array == null || array.size() > RequiredManifest.MAX_ENTRIES) throw new IOException("Invalid requiredMods array");
            List<String> ids = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (JsonElement element : array) {
                if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                    throw new IOException("requiredMods must contain Mod ID strings");
                }
                String id = element.getAsString();
                if (!ManifestEntry.validId(id) || !seen.add(id)) throw new IOException("Invalid or duplicate required Mod ID: " + id);
                ids.add(id);
            }
            Map<String, RequiredEnvironment> overrides = new LinkedHashMap<>();
            if (json.has("environmentOverrides")) {
                JsonObject object = json.getAsJsonObject("environmentOverrides");
                for (var entry : object.entrySet()) {
                    String id = entry.getKey();
                    if (!ManifestEntry.validId(id) || !seen.contains(id)) {
                        throw new IOException("Invalid or unlisted environment override Mod ID: " + id);
                    }
                    JsonElement value = entry.getValue();
                    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                            || !"CLIENT_REQUIRED".equals(value.getAsString())) {
                        throw new IOException("Invalid environment override for " + id + ": only CLIENT_REQUIRED is allowed");
                    }
                    overrides.put(id, RequiredEnvironment.CLIENT_REQUIRED);
                }
            }
            return new Config(ids, overrides);
        } catch (RuntimeException exception) {
            throw new IOException("Invalid Jane server.json", exception);
        }
    }
}
