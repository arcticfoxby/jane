package dev.modsbyfox.jane.core;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Process-local, single-connection permission to request a Jane gate override. */
public final class OneShotJoinOverride {
    private static final Duration LIFETIME = Duration.ofSeconds(90);
    private final Clock clock;
    private final Object clientSession = new Object();
    private Intent intent;
    private Object connection;

    private record Intent(String serverId, String manifestDigest, String selectionDigest,
                          Instant selectionTimestamp, long selectionRevision, SyncSelection selection,
                          String assessmentDigest,
                          Instant issuedAt, Object clientSession) { }

    public OneShotJoinOverride() { this(Clock.systemUTC()); }

    public OneShotJoinOverride(Clock clock) { this.clock = Objects.requireNonNull(clock, "clock"); }

    public static String manifestDigest(RequiredManifest manifest) {
        Objects.requireNonNull(manifest, "manifest");
        try {
            byte[] bytes = ManifestCodec.encode(manifest);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalArgumentException("Cannot digest Jane manifest", exception);
        }
    }

    /** Only normal, completely enumerated install mismatches can be explicitly skipped. */
    public static boolean overrideable(RequiredManifest manifest, List<Comparison.Result> results) {
        if (manifest == null || results == null || results.size() != manifest.entries().size()) return false;
        boolean mismatch = false;
        for (int i = 0; i < results.size(); i++) {
            Comparison.Result result = results.get(i);
            if (result == null || !manifest.entries().get(i).equals(result.required())) return false;
            if (result.status() == null || result.localIssue() != null) return false;
            if (result.status() != Comparison.Status.OK && !result.retainedCandidates().isEmpty()) return false;
            switch (result.status()) {
                case MISSING -> {
                    if (result.local() != null || result.localHash() != null) return false;
                    mismatch = true;
                }
                case VERSION_MISMATCH, HASH_MISMATCH -> {
                    if (result.local() == null || result.localHash() == null
                            || !result.localHash().matches("[0-9a-f]{128}")) return false;
                    mismatch = true;
                }
                case OK -> {
                    if (result.local() == null || !result.required().sha512().equals(result.localHash())) return false;
                }
                case FILE_ERROR -> { return false; }
            }
        }
        return mismatch;
    }

    public synchronized boolean arm(String serverId, RequiredManifest manifest,
                                    SyncSelection selection, List<Comparison.Result> results) {
        clear();
        if (serverId == null || !serverId.matches("[0-9a-f]{64}") || selection == null
                || !overrideable(manifest, results)) return false;
        try {
            String digest = manifestDigest(manifest);
            synchronized (selection) {
                String selectionDigest = selection.digest();
                if (!serverId.equals(selection.serverId()) || !digest.equals(selection.manifestDigest())
                        || !selectionDigest.matches("[0-9a-f]{64}")
                        || !allMismatchesDeselected(selection, results)) return false;
                intent = new Intent(serverId, digest, selectionDigest,
                        selection.selectionTimestamp(), selection.revision(), selection,
                        assessmentDigest(results),
                        clock.instant(), clientSession);
            }
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /** Reserve the intent for the next login connection; a second connection invalidates it. */
    public synchronized void beginConnection(Object nextConnection) {
        if (intent == null) return;
        if (nextConnection == null || expired()) { clear(); return; }
        if (connection == null) connection = nextConnection;
        else if (connection != nextConnection) clear();
    }

    /** Always consumes a reserved intent, even when revalidation denies the override. */
    public synchronized boolean consume(Object currentConnection, String serverId,
                                        RequiredManifest manifest, List<Comparison.Result> results) {
        try {
            Intent current = intent;
            return current != null && currentConnection != null && connection == currentConnection
                    && !expired() && current.clientSession() == clientSession
                    && current.serverId().equals(serverId)
                    && current.manifestDigest().equals(manifestDigest(manifest))
                    && selectionUnchanged(current, serverId, results)
                    && overrideable(manifest, results)
                    && current.assessmentDigest().equals(assessmentDigest(results));
        } catch (IllegalArgumentException exception) {
            return false;
        } finally {
            clear();
        }
    }

    public synchronized void clearForConnection(Object currentConnection) {
        if (connection == currentConnection && currentConnection != null) clear();
    }

    public synchronized void clear() {
        intent = null;
        connection = null;
    }

    private boolean expired() {
        Duration age = Duration.between(intent.issuedAt(), clock.instant());
        return age.isNegative() || age.compareTo(LIFETIME) > 0;
    }

    private static boolean selectionUnchanged(Intent intent, String serverId,
                                              List<Comparison.Result> results) {
        SyncSelection selection = intent.selection();
        synchronized (selection) {
            return selection.serverId().equals(serverId)
                    && selection.manifestDigest().equals(intent.manifestDigest())
                    && selection.selectionTimestamp().equals(intent.selectionTimestamp())
                    && selection.revision() == intent.selectionRevision()
                    && selection.digest().equals(intent.selectionDigest())
                    && allMismatchesDeselected(selection, results);
        }
    }

    private static boolean allMismatchesDeselected(SyncSelection selection,
                                                   List<Comparison.Result> results) {
        for (Comparison.Result result : results)
            if (result.status() != Comparison.Status.OK && selection.isSelected(result.required().modId()))
                return false;
        return true;
    }

    /** Captures the observed Required files; any change requires a new user decision. */
    private static String assessmentDigest(List<Comparison.Result> results) {
        StringBuilder value = new StringBuilder();
        for (Comparison.Result result : results) {
            append(value, result.required().modId());
            append(value, result.status().name());
            Comparison.LocalMod local = result.local();
            append(value, local == null ? null : local.modId());
            append(value, local == null ? null : local.version());
            append(value, local == null ? null : local.jar().toString());
            append(value, result.localHash());
            value.append(result.retainedCandidates().size()).append(':');
            for (Comparison.LocalMod retained : result.retainedCandidates()) {
                append(value, retained.modId());
                append(value, retained.version());
                append(value, retained.jar().toString());
            }
        }
        return Hashing.sha256(value.toString());
    }

    private static void append(StringBuilder value, String field) {
        if (field == null) value.append("-1:");
        else value.append(field.length()).append(':').append(field);
    }
}
