package dev.modsbyfox.jane.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;

public final class PathSafety {
    private static final Pattern SAFE_JAR = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]{0,120}\\.jar");
    private static final Pattern RESERVED = Pattern.compile("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\\..*)?");
    private PathSafety() { }

    public static String safeJarName(String name) {
        if (name == null || !SAFE_JAR.matcher(name).matches() || name.contains("..") || RESERVED.matcher(name).matches()) {
            throw new IllegalArgumentException("Unsafe JAR file name");
        }
        return name;
    }

    public static Path existingJarInMods(Path gameDir, Path jar) throws IOException {
        Path modsPath = gameDir.resolve("mods");
        if (Files.isSymbolicLink(modsPath)) {
            throw new IOException("mods directory escaped current instance");
        }
        Path mods = modsPath.toRealPath();
        if (!mods.getParent().equals(gameDir.toRealPath())) {
            throw new IOException("mods directory escaped current instance");
        }
        Path candidate = jar.toAbsolutePath().normalize();
        Path lexicalMods = modsPath.toAbsolutePath().normalize();
        if ((!candidate.getParent().equals(lexicalMods) && !candidate.getParent().equals(mods))
                || Files.isSymbolicLink(candidate)) {
            throw new IOException("Mod origin is not a direct JAR in this instance's mods directory");
        }
        Path real = jar.toRealPath();
        if (!Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)
                || !real.getParent().equals(mods) || !real.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
            throw new IOException("Mod origin is not a direct JAR in this instance's mods directory");
        }
        return real;
    }

    public static Path newJarInMods(Path gameDir, String name) throws IOException {
        Path mods = gameDir.resolve("mods").toRealPath();
        if (!mods.getParent().equals(gameDir.toRealPath()) || Files.isSymbolicLink(gameDir.resolve("mods"))) {
            throw new IOException("mods directory escaped current instance");
        }
        Path candidate = mods.resolve(safeJarName(name)).normalize();
        if (!candidate.getParent().equals(mods)) throw new IOException("JAR escaped mods directory");
        return candidate;
    }

    public static Path janeDirectory(Path gameDir, String... segments) throws IOException {
        Path current = gameDir.toRealPath();
        String[] all = new String[segments.length + 1];
        all[0] = "jane";
        System.arraycopy(segments, 0, all, 1, segments.length);
        for (String segment : all) {
            if (segment == null || !segment.matches("[A-Za-z0-9_-]{1,80}")) throw new IOException("Unsafe Jane directory name");
            Path next = current.resolve(segment);
            if (Files.isSymbolicLink(next)) throw new IOException("Jane directory is a symlink");
            if (!Files.exists(next)) Files.createDirectory(next);
            Path real = next.toRealPath();
            if (!real.getParent().equals(current)) throw new IOException("Jane directory escaped current instance");
            current = real;
        }
        return current;
    }

    public static boolean cmdSafeJarName(String name) {
        try {
            safeJarName(name);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
