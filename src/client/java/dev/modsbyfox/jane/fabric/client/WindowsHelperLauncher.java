package dev.modsbyfox.jane.fabric.client;

import dev.modsbyfox.jane.core.PathSafety;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;

/** Starts a validated local Jane batch and closes Minecraft only after CMD accepts it. */
final class WindowsHelperLauncher {
    private WindowsHelperLauncher() { }

    static void launch(Minecraft client, Path gameDir, String title, String scriptName,
                       String... relativeDirectory) throws IOException {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows"))
            throw new IOException("Jane automatic file operations require Windows");
        if (!title.matches("Jane [A-Za-z ]{1,32}") || !scriptName.matches("[a-z-]{1,32}\\.bat"))
            throw new IOException("Invalid helper command");
        Path directory = PathSafety.janeDirectory(gameDir, relativeDirectory);
        Path script = directory.resolve(scriptName);
        if (Files.isSymbolicLink(script) || !Files.isRegularFile(script))
            throw new IOException("Jane helper script is missing");
        StringBuilder relative = new StringBuilder("jane");
        for (String segment : relativeDirectory) relative.append('\\').append(segment);
        relative.append('\\').append(scriptName);
        String command = "start \"" + title + "\" cmd.exe /c " + relative;
        Process starter = new ProcessBuilder("cmd.exe", "/c", command).directory(gameDir.toFile()).start();
        try {
            if (!starter.waitFor(5, TimeUnit.SECONDS) || starter.exitValue() != 0)
                throw new IOException("Windows did not start the Jane helper window");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Jane helper launch interrupted", exception);
        }
        client.stop();
    }
}
