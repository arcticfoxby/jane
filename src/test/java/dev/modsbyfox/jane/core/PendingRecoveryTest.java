package dev.modsbyfox.jane.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PendingRecoveryTest {
    @TempDir Path gameDir;
    private static final String ID = "12345678-1234-1234-1234-123456789abc";
    private static final String SERVER = "b".repeat(64);
    private static final String OTHER = "c".repeat(64);
    private static final String STAMP = "2026-09-18_02-30-15";

    @Test
    void incompletePendingRequiresExplicitRetryAndReverification() throws Exception {
        Files.createDirectory(gameDir.resolve("mods"));
        Path stage = PathSafety.janeDirectory(gameDir, "staging", ID);
        Path file = Files.writeString(stage.resolve("new.jar"), "new");
        UpdatePlan plan = new UpdatePlan(ID, SERVER, STAMP, List.of(
                new UpdatePlan.Operation("create", UpdatePlan.Kind.ADD, null, null, "new.jar", Hashing.sha512(file), Files.size(file))));
        PendingStore.create(gameDir, plan);
        assertTrue(Files.readString(gameDir.resolve("jane/pending/" + ID + "/backup.json")).contains("\"oldFile\": null"));
        var items = PendingRecovery.scan(gameDir);
        assertEquals(1, items.size());
        assertFalse(items.get(0).failed());
        assertEquals(plan, items.get(0).plan());
        assertDoesNotThrow(() -> PendingRecovery.verifyForLaunch(gameDir, plan));
        Files.writeString(file, "tampered");
        assertThrows(java.io.IOException.class, () -> PendingRecovery.prepareRetry(gameDir, items.get(0)));
        PendingRecovery.abandon(gameDir, items.get(0));
        assertFalse(Files.exists(gameDir.resolve("jane/pending/" + ID)));
        assertFalse(Files.exists(gameDir.resolve("jane/staging/" + ID)));
    }

    @Test
    void unicodeOldFileRoundTripsAndVerifiesThroughPendingAndBackup() throws Exception {
        Path mods = Files.createDirectory(gameDir.resolve("mods"));
        String oldName = "【农夫乐事】farmers-delight-fabric-1.4.3.jar";
        Path old = Files.writeString(mods.resolve(oldName), "old farmers delight");
        Path staging = PathSafety.janeDirectory(gameDir, "staging", ID);
        Path fresh = Files.writeString(staging.resolve("farmers-delight-refabricated-2.5.4.jar"), "new farmers delight");
        UpdatePlan plan = new UpdatePlan(ID, SERVER, STAMP, List.of(new UpdatePlan.Operation(
                "farmersdelight", UpdatePlan.Kind.REPLACE, oldName, Hashing.sha512(old),
                fresh.getFileName().toString(), Hashing.sha512(fresh), Files.size(fresh))));
        Path pending = PendingStore.create(gameDir, plan);
        assertTrue(Files.readString(pending.resolve("pending.json")).contains(oldName));
        UpdatePlan restored = PendingRecovery.scan(gameDir).get(0).plan();
        assertEquals(oldName, restored.operations().get(0).oldFile());
        assertDoesNotThrow(() -> PendingRecovery.verifyForLaunch(gameDir, restored));

        Path backup = PathSafety.janeDirectory(gameDir, "backups", SERVER, STAMP);
        Files.copy(pending.resolve("backup.json"), backup.resolve("backup.json"));
        Files.move(old, backup.resolve(oldName));
        Files.copy(fresh, mods.resolve(fresh.getFileName()));
        Files.writeString(pending.resolve("success.marker"), "SUCCESS");
        assertTrue(PendingRecovery.scan(gameDir).isEmpty());
        assertTrue(Files.exists(backup.resolve("success.marker")));
        assertFalse(Files.exists(backup.resolve("update.log")), "A missing CMD log must never be invented");
        assertFalse(Files.exists(pending));
    }

    @Test
    void successfulPendingIsVerifiedThenOnlyOldestSuccessfulBackupForThatServerIsRemoved() throws Exception {
        Path mods = Files.createDirectory(gameDir.resolve("mods"));
        Path newJar = Files.writeString(mods.resolve("new.jar"), "new");
        UpdatePlan plan = new UpdatePlan(ID, SERVER, STAMP, List.of(
                new UpdatePlan.Operation("create", UpdatePlan.Kind.ADD, null, null, "new.jar", Hashing.sha512(newJar), Files.size(newJar))));
        Path pending = PendingStore.create(gameDir, plan);
        Path stage = PathSafety.janeDirectory(gameDir, "staging", ID);
        Files.writeString(stage.resolve("new.jar"), "new");
        Path backups = PathSafety.janeDirectory(gameDir, "backups", SERVER);
        String oldest = "2026-09-01_00-00-01";
        for (int i = 1; i <= 5; i++) {
            String stamp = "2026-09-0" + i + "_00-00-01";
            Path prior = PathSafety.janeDirectory(gameDir, "backups", SERVER, stamp);
            Files.writeString(prior.resolve("backup.json"), "{}");
            Files.writeString(prior.resolve("success.marker"), "SUCCESS");
        }
        Path failed = PathSafety.janeDirectory(gameDir, "backups", SERVER, "2026-09-06_00-00-01");
        Files.writeString(failed.resolve("backup.json"), "{}");
        Path other = PathSafety.janeDirectory(gameDir, "backups", OTHER, oldest);
        Files.writeString(other.resolve("success.marker"), "SUCCESS");
        Path current = PathSafety.janeDirectory(gameDir, "backups", SERVER, STAMP);
        Files.copy(pending.resolve("backup.json"), current.resolve("backup.json"));
        String diagnostic = "SYNC " + ID + "\nINSTALL create OK\nSUCCESS\n";
        Files.writeString(pending.resolve("update.log"), diagnostic);
        Files.writeString(pending.resolve("success.marker"), "SUCCESS");

        assertTrue(PendingRecovery.scan(gameDir).isEmpty());
        assertTrue(Files.isRegularFile(current.resolve("success.marker")));
        assertEquals(diagnostic, Files.readString(current.resolve("update.log")),
                "A verified update must retain its original CMD diagnostics after pending cleanup");
        assertFalse(Files.exists(backups.resolve(oldest)));
        assertTrue(Files.exists(failed));
        assertTrue(Files.exists(other));
        assertFalse(Files.exists(pending));
        assertFalse(Files.exists(stage));
    }

    @Test
    void unsafeLogBlocksCleanupWithoutClaimingInstalledJarIsCorrupt() throws Exception {
        UpdatePlan plan = completedAddPlan();
        Path pending = gameDir.resolve("jane/pending/" + ID);
        Files.createDirectory(pending.resolve("update.log"));
        var items = PendingRecovery.scan(gameDir);
        assertEquals(1, items.size());
        assertTrue(items.get(0).issue().contains("Unsafe or oversized pending update log"));
        assertTrue(Files.isDirectory(pending));
        assertTrue(Files.isRegularFile(gameDir.resolve("jane/backups/" + SERVER + "/" + STAMP + "/backup.json")));
        assertFalse(Files.exists(gameDir.resolve("jane/backups/" + SERVER + "/" + STAMP + "/success.marker")));
    }

    @Test
    void oversizedLogLeavesPendingEvidenceForRecovery() throws Exception {
        completedAddPlan();
        Path pending = gameDir.resolve("jane/pending/" + ID);
        try (var file = new java.io.RandomAccessFile(pending.resolve("update.log").toFile(), "rw")) {
            file.setLength(8L * 1024 * 1024 + 1);
        }
        var items = PendingRecovery.scan(gameDir);
        assertEquals(1, items.size());
        assertTrue(items.get(0).issue().contains("Unsafe or oversized pending update log"));
        assertTrue(Files.exists(pending.resolve("update.log")));
        assertFalse(Files.exists(gameDir.resolve("jane/backups/" + SERVER + "/" + STAMP + "/success.marker")));
    }

    @Test
    void symlinkedLogCannotBeCopiedOrCausePendingCleanupWhenSupported() throws Exception {
        completedAddPlan();
        Path pending = gameDir.resolve("jane/pending/" + ID);
        Path untouched = Files.writeString(gameDir.resolve("untouched.log"), "private diagnostic");
        try {
            Files.createSymbolicLink(pending.resolve("update.log"), untouched);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException unsupported) {
            return;
        }
        var items = PendingRecovery.scan(gameDir);
        assertEquals(1, items.size());
        assertTrue(items.get(0).issue().contains("Unsafe or oversized pending update log"));
        assertEquals("private diagnostic", Files.readString(untouched));
        assertTrue(Files.isSymbolicLink(pending.resolve("update.log")));
        assertFalse(Files.exists(gameDir.resolve("jane/backups/" + SERVER + "/" + STAMP + "/update.log")));
    }

    @Test
    void symlinkedBackupLogCannotBeOverwrittenWhenSupported() throws Exception {
        completedAddPlan();
        Path pending = gameDir.resolve("jane/pending/" + ID);
        Files.writeString(pending.resolve("update.log"), "verified updater log");
        Path backup = gameDir.resolve("jane/backups/" + SERVER + "/" + STAMP);
        Path untouched = Files.writeString(gameDir.resolve("untouched.log"), "untouched");
        try {
            Files.createSymbolicLink(backup.resolve("update.log"), untouched);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException unsupported) {
            return;
        }
        var items = PendingRecovery.scan(gameDir);
        assertEquals(1, items.size());
        assertTrue(items.get(0).issue().contains("Unsafe or oversized backup update log"));
        assertEquals("untouched", Files.readString(untouched));
        assertTrue(Files.exists(pending.resolve("update.log")));
        assertFalse(Files.exists(backup.resolve("success.marker")));
    }

    private UpdatePlan completedAddPlan() throws Exception {
        Path mods = Files.createDirectory(gameDir.resolve("mods"));
        Path installed = Files.writeString(mods.resolve("new.jar"), "new");
        UpdatePlan plan = new UpdatePlan(ID, SERVER, STAMP, List.of(new UpdatePlan.Operation(
                "create", UpdatePlan.Kind.ADD, null, null, "new.jar", Hashing.sha512(installed), Files.size(installed))));
        Path pending = PendingStore.create(gameDir, plan);
        Path backup = PathSafety.janeDirectory(gameDir, "backups", SERVER, STAMP);
        Files.copy(pending.resolve("backup.json"), backup.resolve("backup.json"));
        Files.writeString(pending.resolve("success.marker"), "SUCCESS");
        return plan;
    }
}
