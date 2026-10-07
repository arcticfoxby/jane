package dev.modsbyfox.jane.fabric;

import dev.modsbyfox.jane.core.PathSafety;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;

public final class ModOrigins {
    private ModOrigins() { }

    public static Path directJar(ModContainer mod, Path gameDir) throws IOException {
        ModOrigin origin = mod.getOrigin();
        if (origin.getKind() != ModOrigin.Kind.PATH) {
            throw new IOException("Mod " + mod.getMetadata().getId() + " has a nested or unknown origin");
        }
        List<Path> paths = origin.getPaths();
        if (paths.size() != 1) throw new IOException("Mod " + mod.getMetadata().getId() + " has multiple origin paths");
        return PathSafety.existingJarInMods(gameDir, paths.get(0));
    }
}
