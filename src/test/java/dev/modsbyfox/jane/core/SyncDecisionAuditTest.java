package dev.modsbyfox.jane.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SyncDecisionAuditTest {
    @TempDir Path gameDir;

    @Test void recordsUserDecisionWithShortIdsAndWithoutPrivatePaths() throws Exception {
        String serverId = "a".repeat(64);
        String manifestDigest = "b".repeat(64);
        SyncDecisionAudit.record(gameDir, "USER_OVERRIDE_CONFIRMED", Map.of(
                "serverId", serverId, "manifestDigest", manifestDigest, "skippedRequired", "2"));
        SyncDecisionAudit.record(gameDir, "SKIPPED_REQUIRED", Map.of(
                "modId", "create", "requiredVersion", "6.0.8.1", "reason", "USER_DESELECTED"));
        SyncDecisionAudit.record(gameDir, "DEFAULT_OPTIONAL_EXCLUSION", Map.of(
                "modId", "optional_mod", "requiredVersion", gameDir.resolve("private.jar").toString()));

        String content = Files.readString(gameDir.resolve("jane/logs/sync-decisions.log"));
        assertTrue(content.contains("[Jane 1.1.8.2-beta][CLIENT] USER_OVERRIDE_CONFIRMED"));
        assertTrue(content.contains("USER_OVERRIDE_CONFIRMED manifestDigest=" + "b".repeat(12)));
        assertTrue(content.contains("serverId=" + "a".repeat(12)));
        assertTrue(content.contains("SKIPPED_REQUIRED modId=create reason=USER_DESELECTED requiredVersion=6.0.8.1"));
        assertTrue(content.contains("DEFAULT_OPTIONAL_EXCLUSION"));
        assertTrue(content.contains("requiredVersion=[REDACTED]"));
        assertFalse(content.contains(serverId));
        assertFalse(content.contains(manifestDigest));
        assertFalse(content.contains(gameDir.toString()));
        assertArrayEquals(content.getBytes(StandardCharsets.US_ASCII),
                Files.readAllBytes(gameDir.resolve("jane/logs/sync-decisions.log")));
    }

    @Test void directJoinAuditKeepsOnlyShortRequiredHashAndCounts() throws Exception {
        SyncDecisionAudit.record(gameDir, "ENVIRONMENT_CHECK_COMPLETED", Map.of(
                "serverId", "a".repeat(64), "required", "85", "matched", "83",
                "extraClientMods", "4"));
        SyncDecisionAudit.record(gameDir, "SKIPPED_REQUIRED", Map.of(
                "serverId", "a".repeat(64), "modId", "create",
                "requiredVersion", "1.2.3", "comparisonStatus", "MISSING",
                "requiredHash", "b".repeat(12)));
        String content = Files.readString(gameDir.resolve("jane/logs/sync-decisions.log"));
        assertTrue(content.contains("matched=83"));
        assertTrue(content.contains("extraClientMods=4"));
        assertTrue(content.contains("comparisonStatus=MISSING"));
        assertTrue(content.contains("requiredHash=" + "b".repeat(12)));
        assertFalse(content.contains("b".repeat(128)));
    }

    @Test void rotatesBoundedLogs() throws Exception {
        for (int index = 0; index < 2500; index++) {
            SyncDecisionAudit.record(gameDir, "REQUIRED_COMPARISON_COMPLETED", Map.of(
                    "requiredVersion", "x".repeat(80), "required", Integer.toString(index)));
        }
        Path logs = gameDir.resolve("jane/logs");
        assertTrue(Files.exists(logs.resolve("sync-decisions.log.1")));
        for (int index = 0; index <= 3; index++) {
            Path file = logs.resolve("sync-decisions.log" + (index == 0 ? "" : "." + index));
            if (Files.exists(file)) assertTrue(Files.size(file) <= SyncDecisionAudit.MAX_BYTES);
        }
        assertFalse(Files.exists(logs.resolve("sync-decisions.log.4")));
        assertTrue(Files.readString(logs.resolve("sync-decisions.log")).contains("required=2499"));
    }

    @Test void refusesUnsafeAuditTargetAndReportsFailure() throws Exception {
        Path logs = gameDir.resolve("jane/logs");
        Files.createDirectories(logs);
        Files.createDirectory(logs.resolve("sync-decisions.log"));
        assertThrows(IOException.class, () -> SyncDecisionAudit.record(gameDir,
                "USER_DESELECT_REQUIRED", Map.of("modId", "create")));
    }

    @Test void refusesSymlinkedAuditTargetWhenSupported() throws Exception {
        Path logs = gameDir.resolve("jane/logs");
        Files.createDirectories(logs);
        Path outside = gameDir.resolve("untouched.txt");
        Files.writeString(outside, "keep");
        try {
            Files.createSymbolicLink(logs.resolve("sync-decisions.log"), outside);
        } catch (UnsupportedOperationException | IOException | SecurityException unsupported) {
            return;
        }
        assertThrows(IOException.class, () -> SyncDecisionAudit.record(gameDir,
                "USER_DESELECT_REQUIRED", Map.of("modId", "create")));
        assertEquals("keep", Files.readString(outside));
    }

    @Test void rejectsUnapprovedFieldInsteadOfLoggingPossiblySensitiveData() throws Exception {
        assertThrows(IOException.class, () -> SyncDecisionAudit.record(gameDir,
                "USER_OVERRIDE_CONFIRMED", Map.of("token", "secret")));
        assertFalse(Files.exists(gameDir.resolve("jane/logs/sync-decisions.log")));
    }
}
