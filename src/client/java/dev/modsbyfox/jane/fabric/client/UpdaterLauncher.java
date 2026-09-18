package dev.modsbyfox.jane.fabric.client;

import dev.modsbyfox.jane.core.UpdatePlan;
import dev.modsbyfox.jane.core.PathSafety;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;

final class UpdaterLauncher {
    private UpdaterLauncher() { }

    static void launch(Minecraft client, Path gameDir, UpdatePlan plan) throws IOException {
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("windows")) {
            throw new IOException("Jane V1 automatic installation requires Windows");
        }
        Path script = PathSafety.janeDirectory(gameDir, "pending", plan.syncId()).resolve("update.bat");
        if (Files.isSymbolicLink(script) || !Files.isRegularFile(script)) throw new IOException("Jane update script is missing");
        // The command is assembled exclusively from constants and the validated local UUID.
        String command = "start \"Jane updater\" cmd.exe /c jane\\pending\\" + plan.syncId() + "\\update.bat";
        Process starter = new ProcessBuilder("cmd.exe", "/c", command).directory(gameDir.toFile()).start();
        try {
            if (!starter.waitFor(5, TimeUnit.SECONDS) || starter.exitValue() != 0) {
                throw new IOException("Windows did not start the Jane update window");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Jane update launch interrupted", exception);
        }
        client.stop();
    }
}
