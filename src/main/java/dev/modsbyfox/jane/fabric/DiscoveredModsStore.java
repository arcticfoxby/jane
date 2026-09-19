package dev.modsbyfox.jane.fabric;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/** Write-only operator diagnostic; never used as manifest input. */
final class DiscoveredModsStore {
    private DiscoveredModsStore() { }

    static void write(Path gameDir, List<ServerManifest.Classified> classified) throws IOException {
        Path root = gameDir.toRealPath();
        Path config = childDirectory(root, "config");
        Path jane = childDirectory(config, "jane");
        Path target = jane.resolve("discovered-mods.json");
        if (Files.isSymbolicLink(target) || (Files.exists(target) && !Files.isRegularFile(target))) {
            throw new IOException("Jane discovery diagnostic target is unsafe");
        }
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", 1);
        json.addProperty("janeVersion", "1.0.5");
        JsonArray mods = new JsonArray();
        for (ServerManifest.Classified item : classified) {
            var candidate = item.discovered().candidate();
            JsonObject mod = new JsonObject();
            mod.addProperty("modId", candidate.modId());
            mod.addProperty("displayName", candidate.displayName());
            mod.addProperty("version", candidate.version());
            mod.addProperty("fileName", item.discovered().jar().getFileName().toString());
            mod.addProperty("fabricEnvironment", candidate.fabricEnvironment().name());
            mod.addProperty("syncDecision", item.decision().name());
            mod.addProperty("reason", item.reason());
            if (item.jar() != null) {
                mod.addProperty("fileSize", item.jar().size());
                mod.addProperty("sha512", item.jar().sha512());
                if (item.modrinthEnvironment() != null) mod.addProperty("modrinthEnvironment", item.modrinthEnvironment());
                mod.addProperty("modrinthExactMatch", item.modrinthEnvironment() != null);
            }
            mods.add(mod);
        }
        json.add("mods", mods);
        String content = new GsonBuilder().setPrettyPrinting().create().toJson(json) + "\n";
        Path temporary = Files.createTempFile(jane, ".discovered-mods-", ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            if (Files.isSymbolicLink(target)) throw new IOException("Jane discovery diagnostic target became a symlink");
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Path childDirectory(Path parent, String name) throws IOException {
        Path child = parent.resolve(name);
        if (Files.isSymbolicLink(child)) throw new IOException("Jane diagnostic directory is a symlink");
        if (!Files.exists(child)) Files.createDirectory(child);
        Path real = child.toRealPath();
        if (!real.getParent().equals(parent)) throw new IOException("Jane diagnostic directory escaped current instance");
        return real;
    }
}
