package dev.modsbyfox.jane.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Bounded, local audit of sync decisions; this file never determines whether a Mod is valid. */
public final class SyncDecisionAudit {
    static final long MAX_BYTES = 128 * 1024;
    private static final int BACKUPS = 3;
    private static final Pattern EVENT = Pattern.compile("[A-Z][A-Z0-9_]{0,47}");
    private static final Pattern KEY = Pattern.compile("[A-Za-z][A-Za-z0-9]{0,39}");
    private static final Pattern HEX_ID = Pattern.compile("[0-9a-fA-F]{12,128}");
    private static final Pattern MOD_ID = Pattern.compile("[a-z0-9_.-]{1,64}");
    private static final Pattern NUMBER = Pattern.compile("[0-9]{1,12}");
    private static final Set<String> ALLOWED_FIELDS = Set.of(
            "serverId", "manifestDigest", "modId", "requiredVersion", "reason", "required",
            "missing", "versionMismatch", "fileError", "skippedRequired", "selectedRequired",
            "selectedOptional", "defaultOptional", "selectedCount", "downloadCount",
            "requiredBaseline", "joinAction", "selectionTimestamp", "status", "result");

    private SyncDecisionAudit() { }

    /** Appends one sanitized event or throws; callers must report any failure in latest.log and the UI. */
    public static synchronized void record(Path gameDir, String event, Map<String, String> fields) throws IOException {
        byte[] line = format(event, fields).getBytes(StandardCharsets.US_ASCII);
        Path logs = PathSafety.janeDirectory(gameDir, "logs");
        Path target = logs.resolve("sync-decisions.log");
        checkRegularOrAbsent(target);
        for (int index = 1; index <= BACKUPS; index++) checkRegularOrAbsent(backup(target, index));
        long currentSize = Files.exists(target, LinkOption.NOFOLLOW_LINKS) ? Files.size(target) : 0;
        if (currentSize > MAX_BYTES) throw new IOException("Jane sync audit file exceeds size limit");
        if (currentSize + line.length > MAX_BYTES) rotate(target);
        try (FileChannel channel = FileChannel.open(target, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.wrap(line);
            while (buffer.hasRemaining()) channel.write(buffer);
        }
    }

    private static String format(String event, Map<String, String> fields) throws IOException {
        if (event == null || !EVENT.matcher(event).matches() || fields == null || fields.size() > 16) {
            throw new IOException("Invalid Jane sync audit event");
        }
        TreeMap<String, String> sorted = new TreeMap<>();
        for (var field : fields.entrySet()) {
            String key = field.getKey();
            if (key == null || !KEY.matcher(key).matches() || !ALLOWED_FIELDS.contains(key)) {
                throw new IOException("Invalid Jane sync audit field");
            }
            sorted.put(key, field.getValue());
        }
        StringBuilder line = new StringBuilder(Instant.now().toString())
                .append(" [Jane 1.1.8-beta][CLIENT] ").append(event);
        for (var field : sorted.entrySet()) {
            String key = field.getKey();
            line.append(' ').append(key).append('=').append(safeValue(key, field.getValue()));
        }
        return line.append('\n').toString();
    }

    private static String safeValue(String key, String value) {
        if (value == null) return "[REDACTED]";
        if (key.equals("serverId") || key.equals("manifestDigest")) {
            return HEX_ID.matcher(value).matches() ? value.substring(0, 12).toLowerCase(java.util.Locale.ROOT)
                    : "[REDACTED]";
        }
        if (key.equals("modId")) return MOD_ID.matcher(value).matches() ? value : "[REDACTED]";
        if (key.equals("required") || key.equals("missing") || key.equals("versionMismatch")
                || key.equals("fileError") || key.equals("skippedRequired") || key.equals("selectedRequired")
                || key.equals("selectedOptional") || key.equals("defaultOptional")
                || key.equals("selectedCount") || key.equals("downloadCount")) {
            return NUMBER.matcher(value).matches() ? value : "[REDACTED]";
        }
        if (value.length() > 96 || !value.matches("[A-Za-z0-9._+:-]{1,96}")) return "[REDACTED]";
        return value;
    }

    private static void rotate(Path target) throws IOException {
        for (int index = BACKUPS; index > 1; index--) {
            Path previous = backup(target, index - 1);
            if (Files.exists(previous, LinkOption.NOFOLLOW_LINKS)) {
                Files.move(previous, backup(target, index), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            Files.move(target, backup(target, 1), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Path backup(Path target, int index) {
        return target.resolveSibling(target.getFileName() + "." + index);
    }

    private static void checkRegularOrAbsent(Path file) throws IOException {
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)
                && (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("Jane sync audit target is unsafe");
        }
    }
}
