# 简 (Jane) 1.0.0

Jane aligns only the Mod JARs that a multiplayer server explicitly marks as required. It does not synchronize the whole `mods` folder. Unlisted client-only mods, such as Sodium, Iris, maps, HUD mods, and ReplayMod, are left alone.

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
  "requiredMods": []
}
```

A copy is in [`config/jane/server.json`](config/jane/server.json). Add the actual Fabric Mod IDs that every client must match, for example Create and Farmer's Delight if those are required on your server. Jane resolves each ID against loaded mods and computes the top-level JAR version, size, and SHA-512 automatically each time the server starts. The server administrator does not enter filenames, hashes, or download URLs. Jane itself must not appear in `requiredMods`.

Only direct, unique JARs in this server instance's `mods` folder can be required. Missing IDs, ambiguous origins, and nested-only origins make the manifest invalid and cause login to be refused with a configuration message.

The client never sends its complete mod list. A Jane server sends a login query; the client responds only with PASS, MISMATCH, or PROTOCOL_ERROR. On mismatch the login ends before the sync screen opens. Extra client mods do not block login. Server-provided URLs, paths, filenames, and commands are never accepted.

## Local files

Jane uses only the active instance's game directory. The client may create `jane/cache`, `jane/staging`, `jane/pending`, and `jane/backups` beside that instance's `mods` folder. A pending update contains `pending.json`, `backup.json`, and `update.bat`. The BAT logs within that pending directory. Jane never edits another Minecraft instance.

Automatic replacement requires Windows and conservative JAR filenames. If an existing or downloaded filename cannot be represented safely in CMD, Jane stops the automatic path and the player must install it manually.
