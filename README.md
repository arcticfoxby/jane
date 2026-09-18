# 简 (Jane) 1.0.3

Jane aligns only Mod JARs that remote clients truly need for a multiplayer server. It does not synchronize the whole `mods` folder. Extra client mods, such as Sodium, Iris, maps, HUD mods, and ReplayMod, are left alone.

- Minecraft Java Edition **1.20.1**, **Fabric**, **Java 17**.
- Install **Fabric Loader**, **Fabric API**, and **Jane** on both the dedicated server and client. Jane does not install these prerequisites.
- Jane is transparent when its client connects to a server without Jane, and does nothing in singleplayer.
- The server supplies mod ID, version, top-level JAR size, and SHA-512. The client compares only those required entries; equal version strings with different JAR hashes do not pass.
- V1 automatic lookup uses only the exact SHA-512 on **Modrinth**. If it is absent, install the target file manually. Downloads are staged and verified before any replacement is offered.
- On Windows, after the player confirms, a local CMD/BAT helper waits for that Minecraft process to exit, backs up old JARs, installs the verified files, and attempts rollback on failure. It never kills Java processes or restarts Minecraft.
- Successful backups are kept separately by locally generated server ID, up to **five** restore points per server. Pending updates are checked on the next launch; they never run again silently.

## Build

Run `gradlew.bat build` from this directory. The installable JAR is generated under `build/libs/`. Do not install the `-dev.jar` build artifact.

The project pins Gradle 8.7, Fabric Loom 1.6.12, Fabric Loader 0.16.14, Fabric API 0.92.8+1.20.1, and Mojang official mappings. The Gradle Wrapper distribution and wrapper JAR are checked against Gradle's published SHA-256 values.

## Server configuration

On first dedicated-server start, Jane creates `<gameDir>/config/jane/server.json`:

```json
{
  "requiredMods": [],
  "environmentOverrides": {}
}
```

A copy is in [`config/jane/server.json`](config/jane/server.json). `requiredMods` is the set of Mod IDs the administrator asks Jane to examine, not an unconditional list of client requirements. Jane excludes Fabric server-only and client-only declarations. For universal mods, it checks Modrinth's environment metadata for the exact top-level JAR SHA-512. Only `client_and_server` is automatically required on remote clients; server-only and client-optional results are excluded. Jane uses the server's actual JAR version, size, and SHA-512 in the final manifest. The administrator does not enter filenames, hashes, or download URLs. Jane itself must not appear in `requiredMods`.

An exact JAR absent from Modrinth, or returned with an unknown environment, remains `UNKNOWN`. If the administrator knows that this exact JAR is required on remote clients, add `"mod_id": "CLIENT_REQUIRED"` to `environmentOverrides`. Overrides apply **only** to `UNKNOWN` candidates; they cannot override explicit server-only, client-optional, or already client-required metadata. Without an override, `UNKNOWN` makes manifest construction fail with a configuration error. An old config containing only `requiredMods` remains valid.

Jane makes one batch Modrinth environment lookup per manifest build when universal candidates exist. Network errors, non-200 responses, and malformed metadata fail closed: Jane refuses Jane logins rather than silently producing an incomplete required manifest.

Only direct, unique JARs in this server instance's `mods` folder can be required. Missing IDs and ambiguous or nested-only origins for universal candidates make the manifest invalid and cause login to be refused with a configuration message.

The client never sends its complete mod list. A Jane server sends a login query; the client responds only with PASS, MISMATCH, or PROTOCOL_ERROR. On mismatch the login ends before the sync screen opens. Extra client mods do not block login. Server-provided URLs, paths, filenames, and commands are never accepted.

## Local files

Jane uses only the active instance's game directory. The client may create `jane/cache`, `jane/staging`, `jane/pending`, and `jane/backups` beside that instance's `mods` folder. A pending update contains `pending.json`, `backup.json`, and `update.bat`. The BAT logs within that pending directory. Jane never edits another Minecraft instance.

Automatic replacement requires Windows and conservative JAR filenames. If an existing or downloaded filename cannot be represented safely in CMD, Jane stops the automatic path and the player must install it manually.
