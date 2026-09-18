# 简 (Jane) 1.0.3.2

Jane aligns the server Mod JARs that remote clients require, and conservatively includes JARs whose client need cannot be determined. It does not synchronize the whole `mods` folder. Extra client mods, such as Sodium, Iris, maps, HUD mods, and ReplayMod, are left alone.

- Minecraft Java Edition **1.20.1**, **Fabric**, **Java 17**.
- Install **Fabric Loader**, **Fabric API**, and **Jane** on both the dedicated server and client. Jane does not install these prerequisites.
- Jane is transparent when its client connects to a server without Jane, and does nothing in singleplayer.
- The server supplies mod ID, version, top-level JAR size, and SHA-512. The client compares only those required entries; equal version strings with different JAR hashes do not pass.
- V1 automatic lookup uses only the exact SHA-512 on **Modrinth**. If it is absent, install the target file manually. Downloads are staged and verified before any replacement is offered.
- Exact-hash Modrinth downloads can follow a limited number of HTTPS redirects between explicitly approved Modrinth CDN hosts. Jane validates every hop, then checks the final body against the server's exact file size and SHA-512 before staging it.
- On Windows, after the player confirms, a local CMD/BAT helper waits for that Minecraft process to exit, backs up old JARs, installs the verified files, and attempts rollback on failure. It never kills Java processes or restarts Minecraft.
- Successful backups are kept separately by locally generated server ID, up to **five** restore points per server. Pending updates are checked on the next launch; they never run again silently.

## Build

Run `gradlew.bat build` from this directory. The installable JAR is generated under `build/libs/`. Do not install the `-dev.jar` build artifact.

The project pins Gradle 8.7, Fabric Loom 1.6.12, Fabric Loader 0.16.14, Fabric API 0.92.8+1.20.1, and Mojang official mappings. The Gradle Wrapper distribution and wrapper JAR are checked against Gradle's published SHA-256 values.

## Server configuration

On first dedicated-server start, Jane creates `<gameDir>/config/jane/server.json`:

```json
{
  "mode": "AUTO_DISCOVER"
}
```

A copy is in [`config/jane/server.json`](config/jane/server.json). Jane automatically discovers loaded, top-level JARs directly in this server instance's `mods` directory. Nested mods, Jane, Fabric API, and loader/runtime builtins are excluded. Two independent top-level mod identities in the same JAR cause discovery to fail clearly. Jane does not enumerate the `mods` directory to guess identities.

Fabric `SERVER` mods are excluded before hashing or network lookup. Fabric `CLIENT` mods are also excluded, with a warning because they are unexpected on a dedicated server. For universal mods, Jane hashes the actual top-level JAR and queries Modrinth by exact SHA-512. `client_and_server` enters the client manifest. Server-only, client-optional, client-only, and singleplayer-only environments are excluded. A successful lookup with no matching hash, `unknown`, or a future environment value enters the manifest **conservatively**. This may require a client to install a mod whose client need is not yet confirmed. It never means an unknown JAR is safe to run.

Legacy `requiredMods` and `environmentOverrides` fields remain accepted but are ignored; Jane never deletes them. The write-only diagnostic `<gameDir>/config/jane/discovered-mods.json` records each discovered mod's decision and reason without absolute paths. It is never used as input. Modrinth requests are split into batches of at most 100 hashes; a network error, non-200 response, oversized body, or malformed metadata fails the entire manifest. Protocol 1 supports at most 128 final client-sync entries.

An exact hash absent from Modrinth still enters the server's required manifest, but the current client marks it `UNRESOLVED` for manual handling. Jane 1.0.3.2 has no ServerProvider or server-to-client JAR transfer; that would require a separate future design and explicit player confirmation.

The client never sends its complete mod list. A Jane server sends a login query; the client responds only with PASS, MISMATCH, or PROTOCOL_ERROR. On mismatch the login ends before the sync screen opens. Extra client mods do not block login. Server-provided URLs, paths, filenames, and commands are never accepted.

## Local files

Jane uses only the active instance's game directory. The client may create `jane/cache`, `jane/staging`, `jane/pending`, and `jane/backups` beside that instance's `mods` folder. A pending update contains `pending.json`, `backup.json`, and `update.bat`. The BAT logs within that pending directory. Jane never edits another Minecraft instance.

Automatic replacement requires Windows and conservative JAR filenames. If an existing or downloaded filename cannot be represented safely in CMD, Jane stops the automatic path and the player must install it manually.
