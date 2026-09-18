package dev.modsbyfox.jane.fabric;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.modsbyfox.jane.core.ManifestEntry;
import dev.modsbyfox.jane.core.RequiredManifest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class ServerConfig {
    private ServerConfig() { }

    static List<String> read(Path gameDir, Path configDir) throws IOException {
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
            Files.writeString(path, "{\n  \"requiredMods\": []\n}\n", StandardCharsets.UTF_8);
        }
        if (Files.size(path) > 64 * 1024) throw new IOException("Jane server config is too large");
        try {
            JsonObject json = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray array = json.getAsJsonArray("requiredMods");
            if (array == null || array.size() > RequiredManifest.MAX_ENTRIES) throw new IOException("Invalid requiredMods array");
            List<String> ids = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (JsonElement element : array) {
                String id = element.getAsString();
                if (!ManifestEntry.validId(id) || !seen.add(id)) throw new IOException("Invalid or duplicate required Mod ID: " + id);
                ids.add(id);
            }
            return List.copyOf(ids);
        } catch (RuntimeException exception) {
            throw new IOException("Invalid Jane server.json", exception);
        }
    }
}
