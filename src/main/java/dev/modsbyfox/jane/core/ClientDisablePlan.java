package dev.modsbyfox.jane.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** A local-only, exact-file disable operation, separate from server-required updates. */
public record ClientDisablePlan(String serverId, String timestamp, List<Entry> entries) {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    /** The Loader adapter rereads the physical JAR identity on the background preparation thread. */
    @FunctionalInterface
    public interface PhysicalVerifier {
        boolean matches(ClientCompatibilityReport.ExtraMod expected, Path verifiedJar) throws IOException;
    }

    public record Entry(String modId, String displayName, String version, String filename, String sha512) {
        public Entry {
            if (!ManifestEntry.validId(modId) || displayName == null || displayName.isBlank()
                    || version == null || version.isBlank() || sha512 == null
                    || !sha512.matches("[0-9a-f]{128}")) throw new IllegalArgumentException("Invalid disable entry");
            PathSafety.safeExistingJarName(filename);
        }
    }

    public ClientDisablePlan {
        if (serverId == null || !serverId.matches("[0-9a-f]{64}") || timestamp == null
                || !timestamp.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}_[0-9]{2}-[0-9]{2}-[0-9]{2}"))
            throw new IllegalArgumentException("Invalid disable identity");
        entries = List.copyOf(entries);
        if (entries.isEmpty() || entries.size() > RequiredManifest.MAX_ENTRIES)
            throw new IllegalArgumentException("Invalid disable entry count");
        Set<String> names = new HashSet<>();
        for (Entry entry : entries)
            if (!names.add(entry.filename().toLowerCase(Locale.ROOT)))
                throw new IllegalArgumentException("Duplicate disable filename");
    }

    public static ClientDisablePlan prepare(Path gameDir, String serverId, ClientCompatibilityReport report,
                                            List<ClientCompatibilityReport.ExtraMod> selected) throws IOException {
        return prepareChecked(gameDir, serverId, null, report, selected, null);
    }

    /** Selective disable for both explicit-client and universal extras, never server Required files. */
    public static ClientDisablePlan prepare(Path gameDir, String serverId, RequiredManifest required,
                                            ClientCompatibilityReport report,
                                            List<ClientCompatibilityReport.ExtraMod> selected,
                                            PhysicalVerifier verifier) throws IOException {
        if (required == null || verifier == null) throw new IOException("Missing disable verification context");
        return prepareChecked(gameDir, serverId, required, report, selected, verifier);
    }

    private static ClientDisablePlan prepareChecked(Path gameDir, String serverId, RequiredManifest required,
                                                    ClientCompatibilityReport report,
                                                    List<ClientCompatibilityReport.ExtraMod> selected,
                                                    PhysicalVerifier verifier) throws IOException {
        if (report == null || selected == null || selected.isEmpty()) throw new IOException("No client mods selected");
        if (selected.size() > RequiredManifest.MAX_ENTRIES) throw new IOException("Too many client mods selected");
        if (serverId == null || !serverId.matches("[0-9a-f]{64}")) throw new IOException("Invalid disable server identity");
        List<Entry> entries = new ArrayList<>();
        Set<String> filenames = new HashSet<>();
        Set<Path> paths = new HashSet<>();
        Set<String> fileKeys = new HashSet<>();
        for (ClientCompatibilityReport.ExtraMod mod : selected) {
            if (mod == null || mod.kind() == null) throw new IOException("Invalid extra selection");
            boolean explicit = mod.kind() == ClientCompatibilityReport.Kind.EXPLICIT_CLIENT;
            if (explicit ? !report.explicitClientMods().contains(mod)
                    : required == null || !report.otherExtraMods().contains(mod))
                throw new IOException("Selection is not in the current extra report");
            if ("jane".equals(mod.modId())) throw new IOException("Jane cannot be disabled");
            if (required != null && required.entries().stream().anyMatch(entry -> entry.modId().equals(mod.modId())))
                throw new IOException("Required mod cannot be disabled");
            if (explicit && mod.sha512() == null) throw new IOException("Selected extra JAR is unverified");
            if (!explicit && mod.fileIdentity() == null) throw new IOException("Selected extra JAR is unverified");
            String filename = PathSafety.safeExistingJarName(mod.filename());
            Path jar = PathSafety.existingJarInMods(gameDir, mod.jar());
            if (!jar.getFileName().toString().equals(filename)) throw new IOException("Selected extra JAR changed");
            if (!filenames.add(filename.toLowerCase(Locale.ROOT)) || !paths.add(jar))
                throw new IOException("Duplicate extra JAR selection");
            ClientCompatibilityReport.FileIdentity before = ClientCompatibilityReport.FileIdentity.capture(jar);
            if (before.size() <= 0 || before.size() > ManifestEntry.MAX_FILE_SIZE)
                throw new IOException("Selected extra JAR size is invalid");
            if (before.fileKey() != null && !fileKeys.add(before.fileKey()))
                throw new IOException("Duplicate extra JAR selection");
            if (mod.fileIdentity() != null && !mod.fileIdentity().equals(before))
                throw new IOException("Selected extra JAR changed");
            if (verifier != null) {
                try {
                    if (!verifier.matches(mod, jar)) throw new IOException("Selected extra JAR metadata changed");
                } catch (IOException | IllegalArgumentException exception) {
                    throw new IOException("Selected extra JAR metadata changed");
                }
            }
            String hash;
            try {
                hash = Hashing.sha512(jar);
            } catch (IOException exception) {
                throw new IOException("Selected extra JAR cannot be hashed");
            }
            if (!jar.equals(PathSafety.existingJarInMods(gameDir, mod.jar()))
                    || !before.equals(ClientCompatibilityReport.FileIdentity.capture(jar)))
                throw new IOException("Selected extra JAR changed");
            if (!hash.matches("[0-9a-f]{128}") || explicit && !hash.equals(mod.sha512()))
                throw new IOException("Selected extra JAR changed");
            entries.add(new Entry(mod.modId(), mod.displayName(), mod.version(), filename, hash));
        }
        Path parent = PathSafety.janeDirectory(gameDir, "disabled", serverId);
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < 60; i++) {
            String timestamp = now.plusSeconds(i).format(STAMP);
            if (!Files.exists(parent.resolve(timestamp), LinkOption.NOFOLLOW_LINKS))
                return new ClientDisablePlan(serverId, timestamp, entries);
        }
        throw new IOException("No free disable timestamp");
    }
}
