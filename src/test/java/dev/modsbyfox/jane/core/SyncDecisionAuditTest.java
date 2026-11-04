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
        assertTrue(content.contains("[Jane 1.1.8.4-beta][CLIENT] USER_OVERRIDE_CONFIRMED"));
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

    @Test void recordsRequestedCompletedAndFailedRouteSwitches() throws Exception {
        Map<String, String> switchFields = Map.of("from", "TRUSTED_SOURCE", "to", "CURRENT_SERVER",
                "readyCount", "12", "remainingCount", "70",
                "serverId", "a".repeat(64), "manifestDigest", "b".repeat(64));
        SyncDecisionAudit.record(gameDir, "USER_REQUEST_ROUTE_SWITCH", switchFields);
        SyncDecisionAudit.record(gameDir, "DOWNLOAD_ROUTE_SWITCH_COMPLETED", switchFields);
        SyncDecisionAudit.record(gameDir, "DOWNLOAD_ROUTE_SWITCH_FAILED", Map.of(
                "from", "CURRENT_SERVER", "to", "TRUSTED_SOURCE",
                "readyCount", "13", "remainingCount", "69", "reason", "OLD_TRANSFER_FAILED"));
        SyncDecisionAudit.record(gameDir, "DOWNLOAD_ROUTE_SWITCH_FAILED", Map.of(
                "from", "NONE", "to", "CURRENT_SERVER",
                "readyCount", "0", "remainingCount", "1", "reason", "SOURCE_UNAVAILABLE_OR_COMPLETE"));
        SyncDecisionAudit.record(gameDir, "DOWNLOAD_ROUTE_SWITCH_FAILED", Map.of(
                "from", "TRUSTED_SOURCE", "to", "CURRENT_SERVER",
                "readyCount", "12", "remainingCount", "70", "reason", "TRANSFER_ALREADY_COMPLETED"));

        String content = Files.readString(gameDir.resolve("jane/logs/sync-decisions.log"));
        assertTrue(content.contains("USER_REQUEST_ROUTE_SWITCH"));
        assertTrue(content.contains("DOWNLOAD_ROUTE_SWITCH_COMPLETED"));
        assertTrue(content.contains("DOWNLOAD_ROUTE_SWITCH_FAILED"));
        assertTrue(content.contains("from=TRUSTED_SOURCE"));
        assertTrue(content.contains("to=CURRENT_SERVER"));
        assertTrue(content.contains("readyCount=12"));
        assertTrue(content.contains("remainingCount=70"));
        assertTrue(content.contains("reason=OLD_TRANSFER_FAILED"));
        assertTrue(content.contains("reason=SOURCE_UNAVAILABLE_OR_COMPLETE"));
        assertTrue(content.contains("reason=TRANSFER_ALREADY_COMPLETED"));
        assertTrue(content.contains("from=NONE"));
        assertTrue(content.contains("serverId=" + "a".repeat(12)));
        assertTrue(content.contains("manifestDigest=" + "b".repeat(12)));
        assertEquals(5, content.lines().count());
        assertEquals(1, content.lines().filter(line -> line.contains("DOWNLOAD_ROUTE_SWITCH_COMPLETED")).count());
        assertEquals(3, content.lines().filter(line -> line.contains("DOWNLOAD_ROUTE_SWITCH_FAILED")).count());
        assertTrue(content.lines().filter(line -> line.contains("DOWNLOAD_ROUTE_SWITCH_COMPLETED"))
                .noneMatch(line -> line.contains("reason=")));
    }

    @Test void rejectsInvalidRouteNamesAndCountsBeforeCreatingAuditFile() {
        for (String route : new String[] { "trusted_source", "LATEST", "C:/private", "TRUSTED_SOURCE\nTOKEN" }) {
            assertThrows(IOException.class, () -> SyncDecisionAudit.record(gameDir,
                    "USER_REQUEST_ROUTE_SWITCH", Map.of("from", route, "to", "NONE")));
            assertThrows(IOException.class, () -> SyncDecisionAudit.record(gameDir,
                    "USER_REQUEST_ROUTE_SWITCH", Map.of("from", "NONE", "to", route)));
        }
        for (String count : new String[] { "-1", "+1", "1.0", "1234567890123" }) {
            assertThrows(IOException.class, () -> SyncDecisionAudit.record(gameDir,
                    "DOWNLOAD_ROUTE_SWITCH_FAILED", Map.of("readyCount", count)));
            assertThrows(IOException.class, () -> SyncDecisionAudit.record(gameDir,
                    "DOWNLOAD_ROUTE_SWITCH_FAILED", Map.of("remainingCount", count)));
        }
        assertFalse(Files.exists(gameDir.resolve("jane/logs/sync-decisions.log")));
    }

    @Test void redactsUnsafeFailureReasonRatherThanWritingPathsOrControls() throws Exception {
        for (String reason : new String[] { gameDir.resolve("private.jar").toString(), "BAD\nVALUE" }) {
            SyncDecisionAudit.record(gameDir, "DOWNLOAD_ROUTE_SWITCH_FAILED", Map.of(
                    "from", "TRUSTED_SOURCE", "to", "CURRENT_SERVER",
                    "readyCount", "0", "remainingCount", "1", "reason", reason));
        }
        String content = Files.readString(gameDir.resolve("jane/logs/sync-decisions.log"));
        assertFalse(content.contains(gameDir.toString()));
        assertFalse(content.contains("BAD\nVALUE"));
        assertEquals(2, content.lines().count());
        assertEquals(2, content.split("reason=\\[REDACTED\\]", -1).length - 1);
    }

    @Test void acceptsValidatedExtraDisableFieldsAndRejectsUnsafeValues() throws Exception {
        SyncDecisionAudit.record(gameDir, "EXTRA_DISABLE_FILE_VERIFIED", Map.of(
                "modId", "twilightforest", "kind", "OTHER_EXTRA", "fileHash", "A".repeat(12),
                "selectedExplicit", "1", "selectedOther", "2"));
        String content = Files.readString(gameDir.resolve("jane/logs/sync-decisions.log"));
        assertTrue(content.contains("kind=OTHER_EXTRA"));
        assertTrue(content.contains("fileHash=" + "a".repeat(12)));
        assertTrue(content.contains("selectedExplicit=1"));
        assertTrue(content.contains("selectedOther=2"));
        for (Map<String, String> fields : java.util.List.<Map<String, String>>of(
                Map.of("kind", "UNKNOWN"), Map.of("fileHash", "not-a-hash"),
                Map.of("selectedExplicit", "-1"), Map.of("selectedOther", "-1"))) {
            assertThrows(IOException.class, () -> SyncDecisionAudit.record(gameDir,
                    "EXTRA_DISABLE_FILE_VERIFIED", fields));
        }
        assertEquals(1, Files.readString(gameDir.resolve("jane/logs/sync-decisions.log")).lines().count());
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
