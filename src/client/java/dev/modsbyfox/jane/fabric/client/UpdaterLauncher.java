package dev.modsbyfox.jane.fabric.client;

import dev.modsbyfox.jane.core.UpdatePlan;
import java.io.IOException;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;

final class UpdaterLauncher {
    private UpdaterLauncher() { }

    static void launch(Minecraft client, Path gameDir, UpdatePlan plan) throws IOException {
        WindowsHelperLauncher.launch(client, gameDir, "Jane updater", "update.bat", "pending", plan.syncId());
    }
}
