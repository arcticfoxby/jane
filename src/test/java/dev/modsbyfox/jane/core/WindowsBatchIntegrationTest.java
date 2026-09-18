package dev.modsbyfox.jane.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WindowsBatchIntegrationTest {
    @TempDir Path root;
    Path gameDir;
    private static final String ID = "12345678-1234-1234-1234-123456789abc";
    private static final String SERVER = "b".repeat(64);

    @BeforeEach
    void createInstanceWithShellCharactersInItsPath() throws Exception {
        gameDir = Files.createDirectory(root.resolve("中文 & % ^ ( ) !"));
    }

    @Test
    void installsOnlyAfterBackupAndMarksSuccess() throws Exception {
        Path mods = Files.createDirectory(gameDir.resolve("mods"));
        Path old = Files.writeString(mods.resolve("old.jar"), "old");
        Path stage = Files.createDirectories(gameDir.resolve("jane/staging/" + ID));
        Path fresh = Files.writeString(stage.resolve("new.jar"), "new");
        UpdatePlan plan = plan(List.of(new UpdatePlan.Operation("create", UpdatePlan.Kind.REPLACE,
                "old.jar", Hashing.sha512(old), "new.jar", Hashing.sha512(fresh), Files.size(fresh))));
        int exit = run(plan);
        assertEquals(0, exit);
        assertFalse(Files.exists(old));
        assertEquals("new", Files.readString(mods.resolve("new.jar")));
        assertEquals("old", Files.readString(gameDir.resolve("jane/backups/" + SERVER + "/" + plan.timestamp() + "/old.jar")));
        assertTrue(Files.isRegularFile(gameDir.resolve("jane/pending/" + ID + "/success.marker")));
        String log = Files.readString(gameDir.resolve("jane/pending/" + ID + "/update.log"));
        assertTrue(log.contains("BACKUP create OK"));
        assertTrue(log.contains("INSTALL create OK"));
        assertTrue(log.contains("SUCCESS"));
    }

    @Test
    void rollsBackWhenLaterInstallFails() throws Exception {
        Path mods = Files.createDirectory(gameDir.resolve("mods"));
        Path a = Files.writeString(mods.resolve("a-old.jar"), "a-old");
        Path b = Files.writeString(mods.resolve("b-old.jar"), "b-old");
        Path stage = Files.createDirectories(gameDir.resolve("jane/staging/" + ID));
        Path newA = Files.writeString(stage.resolve("a-new.jar"), "a-new");
        UpdatePlan plan = plan(List.of(
                new UpdatePlan.Operation("alpha", UpdatePlan.Kind.REPLACE, "a-old.jar", Hashing.sha512(a),
                        "a-new.jar", Hashing.sha512(newA), Files.size(newA)),
                new UpdatePlan.Operation("beta", UpdatePlan.Kind.REPLACE, "b-old.jar", Hashing.sha512(b),
                        "b-new.jar", "c".repeat(128), 5)));
        int exit = run(plan);
        assertNotEquals(0, exit);
        assertEquals("a-old", Files.readString(a));
        assertEquals("b-old", Files.readString(b));
        assertFalse(Files.exists(mods.resolve("a-new.jar")));
        assertFalse(Files.exists(mods.resolve("b-new.jar")));
        assertTrue(Files.isRegularFile(gameDir.resolve("jane/pending/" + ID + "/failed.marker")));
        assertFalse(Files.exists(gameDir.resolve("jane/pending/" + ID + "/success.marker")));
        String log = Files.readString(gameDir.resolve("jane/pending/" + ID + "/update.log"));
        assertTrue(log.contains("ROLLBACK START"));
        assertTrue(log.contains("FAILED"));
    }

    @Test
    void backupFailureRestoresEarlierJarWithoutDeletingAnUntouchedJar() throws Exception {
        Path mods = Files.createDirectory(gameDir.resolve("mods"));
        Path a = Files.writeString(mods.resolve("a.jar"), "a-old");
        Path stage = Files.createDirectories(gameDir.resolve("jane/staging/" + ID));
        Path newA = Files.writeString(stage.resolve("a.jar"), "a-new");
        Path newB = Files.writeString(stage.resolve("b.jar"), "b-new");
        UpdatePlan plan = plan(List.of(
                new UpdatePlan.Operation("alpha", UpdatePlan.Kind.REPLACE, "a.jar", Hashing.sha512(a),
                        "a.jar", Hashing.sha512(newA), Files.size(newA)),
                new UpdatePlan.Operation("beta", UpdatePlan.Kind.REPLACE, "missing.jar", "d".repeat(128),
                        "b.jar", Hashing.sha512(newB), Files.size(newB))));
        assertNotEquals(0, run(plan));
        assertEquals("a-old", Files.readString(a));
        assertFalse(Files.exists(mods.resolve("b.jar")));
    }

    @Test
    void generatedBatRetainsDetailedProgressAndWaitsForManualExit() {
        UpdatePlan plan = plan(List.of(new UpdatePlan.Operation("create", UpdatePlan.Kind.REPLACE,
                "old.jar", "a".repeat(128), "new.jar", "b".repeat(128), 3)));
        String bat = WindowsBatch.generate(plan, 2147483647L);
        assertTrue(bat.contains("tasklist /FI \"PID eq 2147483647\""));
        assertTrue(bat.contains("正在等待 Minecraft 关闭..."));
        assertTrue(bat.contains("Minecraft 已关闭。"));
        assertTrue(bat.contains("正在备份旧模组... 1/1"));
        assertTrue(bat.contains("正在安装新模组... 1/1"));
        assertTrue(bat.contains("更新完成。"));
        assertTrue(bat.contains("恢复点：" + plan.timestamp()));
        assertTrue(bat.contains("请您手动重启客户端。"));
        assertTrue(bat.contains("按任意键退出..."));
        assertTrue(bat.contains("pause >nul\r\nexit /b 0"));
        assertTrue(bat.contains("pause >nul\r\nexit /b 1"));
        assertFalse(bat.contains("timeout /t 5 /nobreak"));
        assertFalse(bat.contains("taskkill"));
        assertTrue(bat.contains("setlocal DisableDelayedExpansion"));
        assertTrue(bat.indexOf("success.marker") < bat.indexOf("echo 更新完成。"));
        assertTrue(bat.indexOf("echo SUCCESS") < bat.indexOf("echo 更新完成。"));
    }

    @Test
    void successMarkerExistsWhileUpdaterWaitsForPlayerKey() throws Exception {
        Files.createDirectory(gameDir.resolve("mods"));
        Path stage = Files.createDirectories(gameDir.resolve("jane/staging/" + ID));
        Path fresh = Files.writeString(stage.resolve("new.jar"), "new");
        UpdatePlan plan = plan(List.of(new UpdatePlan.Operation("create", UpdatePlan.Kind.ADD,
                null, null, "new.jar", Hashing.sha512(fresh), Files.size(fresh))));
        Path pending = Files.createDirectories(gameDir.resolve("jane/pending/" + ID));
        Files.writeString(pending.resolve("backup.json"), "{}", StandardCharsets.UTF_8);
        Files.writeString(pending.resolve("update.bat"), WindowsBatch.generate(plan, 2147483647L), StandardCharsets.UTF_8);
        Process process = new ProcessBuilder("cmd.exe", "/c", "jane\\pending\\" + ID + "\\update.bat")
                .directory(gameDir.toFile()).redirectErrorStream(true).start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (process.isAlive() && System.nanoTime() < deadline
                    && (!Files.exists(pending.resolve("success.marker"))
                    || !Files.exists(pending.resolve("update.log"))
                    || !Files.readString(pending.resolve("update.log")).contains("SUCCESS"))) {
                Thread.sleep(20);
            }
            assertTrue(Files.exists(pending.resolve("success.marker")), "Installation should finish before the exit prompt");
            assertTrue(Files.readString(pending.resolve("update.log")).contains("SUCCESS"));
            assertTrue(process.isAlive(), "CMD should wait for player input after success");
            process.getOutputStream().write('\n');
            process.getOutputStream().close();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private UpdatePlan plan(List<UpdatePlan.Operation> operations) {
        return new UpdatePlan(ID, SERVER, "2026-09-18_02-30-15", operations);
    }

    private int run(UpdatePlan plan) throws Exception {
        Path pending = Files.createDirectories(gameDir.resolve("jane/pending/" + ID));
        Files.writeString(pending.resolve("backup.json"), "{}", StandardCharsets.UTF_8);
        Files.writeString(pending.resolve("update.bat"), WindowsBatch.generate(plan, 2147483647L), StandardCharsets.UTF_8);
        Process process = new ProcessBuilder("cmd.exe", "/c", "jane\\pending\\" + ID + "\\update.bat")
                .directory(gameDir.toFile()).redirectErrorStream(true).start();
        process.getOutputStream().write('\n');
        process.getOutputStream().close();
        boolean done = process.waitFor(30, TimeUnit.SECONDS);
        if (!done) process.destroyForcibly();
        assertTrue(done, "Updater BAT exceeded " + Duration.ofSeconds(30));
        return process.exitValue();
    }
}
