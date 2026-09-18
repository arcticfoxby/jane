package dev.modsbyfox.jane.fabric;

import dev.modsbyfox.jane.core.Hashing;
import dev.modsbyfox.jane.core.ManifestEntry;
import dev.modsbyfox.jane.core.RequiredManifest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

final class ServerManifest {
    private ServerManifest() { }

    static RequiredManifest build() throws IOException {
        FabricLoader loader = FabricLoader.getInstance();
        Path gameDir = loader.getGameDir();
        ArrayList<ManifestEntry> entries = new ArrayList<>();
        for (String id : ServerConfig.read(gameDir, loader.getConfigDir())) {
            ModContainer mod = loader.getModContainer(id).orElseThrow(() -> new IOException("Required mod not loaded: " + id));
            Path jar = ModOrigins.directJar(mod, gameDir);
            long size = Files.size(jar);
            long modified = Files.getLastModifiedTime(jar).toMillis();
            String hash = Hashing.sha512(jar);
            if (Files.size(jar) != size || Files.getLastModifiedTime(jar).toMillis() != modified) {
                throw new IOException("Required JAR changed while hashing: " + id);
            }
            entries.add(new ManifestEntry(id, mod.getMetadata().getName(), mod.getMetadata().getVersion().getFriendlyString(),
                    size, hash));
        }
        return new RequiredManifest(RequiredManifest.PROTOCOL, entries);
    }
}
