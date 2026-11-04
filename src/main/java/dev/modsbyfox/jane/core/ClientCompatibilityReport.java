package dev.modsbyfox.jane.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Local-only inventory of physical JARs outside the server-required baseline. */
public record ClientCompatibilityReport(List<ExtraMod> explicitClientMods, List<ExtraMod> otherExtraMods,
                                        String fingerprint) {
    public enum Kind { EXPLICIT_CLIENT, OTHER_EXTRA }
    /** Cheap discovery-time identity; no JAR content is read for an OTHER_EXTRA. */
    public record FileIdentity(long size, FileTime modifiedTime, String fileKey) {
        public FileIdentity {
            if (size < 0 || modifiedTime == null) throw new IllegalArgumentException("Invalid extra file identity");
        }

        public static FileIdentity capture(Path jar) throws IOException {
            BasicFileAttributes attributes = Files.readAttributes(jar, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.isSymbolicLink())
                throw new IOException("Selected extra JAR is not an ordinary file");
            Object key = attributes.fileKey();
            return new FileIdentity(attributes.size(), attributes.lastModifiedTime(), key == null ? null : key.toString());
        }
    }

    public record ExtraMod(String modId, String displayName, String version, String filename,
                           String sha512, Kind kind, Path jar, FileIdentity fileIdentity) {
        public ExtraMod(String modId, String displayName, String version, String filename,
                        String sha512, Kind kind, Path jar) {
            this(modId, displayName, version, filename, sha512, kind, jar, null);
        }

        public ExtraMod {
            if (!ManifestEntry.validId(modId) || displayName == null || displayName.isBlank()
                    || version == null || version.isBlank() || kind == null || jar == null
                    || (kind == Kind.EXPLICIT_CLIENT && sha512 != null && !sha512.matches("[0-9a-f]{128}"))
                    || (kind == Kind.OTHER_EXTRA && sha512 != null))
                throw new IllegalArgumentException("Invalid extra mod");
            if (filename == null || filename.isBlank()) throw new IllegalArgumentException("Invalid extra filename");
        }
    }

    public ClientCompatibilityReport {
        explicitClientMods = List.copyOf(explicitClientMods);
        otherExtraMods = List.copyOf(otherExtraMods);
        for (ExtraMod mod : explicitClientMods)
            if (mod.kind() != Kind.EXPLICIT_CLIENT) throw new IllegalArgumentException("Incorrect explicit kind");
        for (ExtraMod mod : otherExtraMods)
            if (mod.kind() != Kind.OTHER_EXTRA) throw new IllegalArgumentException("Incorrect other kind");
        Objects.requireNonNull(fingerprint, "fingerprint");
        if (!fingerprint.matches("[0-9a-f]{128}")) throw new IllegalArgumentException("Invalid fingerprint");
    }

    public static String fingerprint(List<ExtraMod> explicit) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            for (ExtraMod mod : explicit.stream().sorted(Comparator.comparing(ExtraMod::modId)
                    .thenComparing(ExtraMod::version)
                    .thenComparing(ExtraMod::sha512, Comparator.nullsFirst(Comparator.naturalOrder()))).toList()) {
                digest.update((mod.modId() + "\0" + mod.version() + "\0"
                        + (mod.sha512() == null ? "unverified" : mod.sha512()) + "\0"
                        + identityValue(mod.fileIdentity()) + "\n")
                        .getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static String identityValue(FileIdentity identity) {
        if (identity == null) return "unknown";
        return identity.size() + ":" + identity.modifiedTime() + ":"
                + (identity.fileKey() == null ? "" : identity.fileKey());
    }
}
