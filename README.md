# 简 (Jane) 1.0.5

Jane aligns the server Mod JARs that remote clients require, and conservatively includes JARs whose client need cannot be determined. It does not synchronize the whole `mods` folder. Extra client mods, such as Sodium, Iris, maps, HUD mods, and ReplayMod, are left alone.

- Minecraft Java Edition **1.20.1**, **Fabric**, **Java 17**.
- Install **Fabric Loader**, **Fabric API**, and **Jane** on both the dedicated server and client. Jane does not install these prerequisites.
- Jane is transparent when its client connects to a server without Jane, and does nothing in singleplayer.
- The server supplies mod ID, version, top-level JAR size, and SHA-512. The client compares only those required entries; equal version strings with different JAR hashes do not pass.
- Source lookup starts only after the player selects **Download and prepare files**. It uses the exact SHA-512 on **Modrinth** for each mismatched required file, then automatically stages public files. If the exact file is absent and this server has ServerProvider enabled, Jane offers a separate, explicit, per-session server download confirmation. Downloads are staged and verified before any replacement is offered. A lookup error is unresolved, not evidence that Modrinth lacks the file.
- Exact-hash Modrinth downloads can follow a limited number of HTTPS redirects between explicitly approved Modrinth CDN hosts. Jane validates every hop, then checks the final body against the server's exact file size and SHA-512 before staging it.
- On Windows, after the player confirms, Jane immediately opens a separate CMD updater. It waits for that Minecraft process to exit, displays backup and installation progress, then keeps the window open until the player presses a key. Jane prompts the player to restart Minecraft manually; it never kills Java processes or restarts the game. Failed updates still attempt rollback.
- Successful backups are kept separately by locally generated server ID, up to **five** restore points per server. Pending updates are checked on the next launch; they never run again silently.

## Build

Run `gradlew.bat build` from this directory. The installable JAR is generated under `build/libs/`. Do not install the `-dev.jar` build artifact.

The project pins Gradle 8.7, Fabric Loom 1.6.12, Fabric Loader 0.16.14, Fabric API 0.92.8+1.20.1, and Mojang official mappings. The Gradle Wrapper distribution and wrapper JAR are checked against Gradle's published SHA-256 values.

## Server configuration

On first dedicated-server start, Jane creates `<gameDir>/config/jane/server.json`:

```json
{
  "mode": "AUTO_DISCOVER",
  "serverProvider": {
    "mode": "AUTO"
  }
}
```

A copy is in [`config/jane/server.json`](config/jane/server.json). Jane automatically discovers loaded, top-level JARs directly in this server instance's `mods` directory. Nested mods, Jane, Fabric API, and loader/runtime builtins are excluded. Two independent top-level mod identities in the same JAR cause discovery to fail clearly. Jane does not enumerate the `mods` directory to guess identities.

Fabric `SERVER` mods are excluded before hashing or network lookup. Fabric `CLIENT` mods are also excluded, with a warning because they are unexpected on a dedicated server. For universal mods, Jane hashes the actual top-level JAR and queries Modrinth by exact SHA-512. `client_and_server` enters the client manifest. Server-only, client-optional, client-only, and singleplayer-only environments are excluded. A successful lookup with no matching hash, `unknown`, or a future environment value enters the manifest **conservatively**. This may require a client to install a mod whose client need is not yet confirmed. It never means an unknown JAR is safe to run.

Legacy `requiredMods` and `environmentOverrides` fields remain accepted but are ignored; Jane never deletes them. The write-only diagnostic `<gameDir>/config/jane/discovered-mods.json` records each discovered mod's decision and reason without absolute paths. It is never used as input. Modrinth requests are split into batches of at most 100 hashes; a network error, non-200 response, oversized body, or malformed metadata fails the entire manifest. Protocol 3 supports at most 128 final client-sync entries and rejects older Protocol 1 and 2 clients.

ServerProvider defaults to `AUTO`, which uses the existing Minecraft TCP entry point. No second listener, router port, or FRP mapping is needed for a direct server or transparent TCP forward. The client resolves the captured logical Minecraft address using Minecraft's address/SRV resolver and opens a second connection to that endpoint. A dedicated login marker lets Jane take over only that connection before ordinary login authentication; the marker grants no file access. The server never supplies an arbitrary download host. Status requests and unmarked player logins remain on the ordinary Minecraft path.

The available `serverProvider.mode` values are `AUTO`, `MINECRAFT`, `SEPARATE_PORT`, and `DISABLED`. `AUTO` currently selects `MINECRAFT`. `DISABLED` makes non-public required files unresolved. A legacy `enabled: true` configuration remains a `SEPARATE_PORT` configuration; the exact old generated `enabled: false` default is interpreted as `AUTO`, while a customized disabled configuration stays disabled. Jane leaves existing configuration files untouched and logs the interpretation.

Minecraft-aware proxies, including some BungeeCord/Velocity deployments, may reject the marked login before it reaches Jane. These networks can use the advanced separate-port fallback until proxy-specific support exists. For example, `"serverProvider": {"mode":"SEPARATE_PORT","bindPort":25566,"advertisedPort":41477}` listens locally on 25566 while an administrator routes `example.com:41477` to that port. The client uses its captured Minecraft host plus `advertisedPort`; Jane does not configure NAT, firewall, or FRP. If the separate port cannot bind, startup fails. Only serve JARs you have the right to redistribute.

Each login receives a different memory-only 256-bit token. An unused offer remains valid for up to 60 minutes; after the first successful request, each authorized transfer refreshes a 15-minute idle lifetime. The token permits only hashes in that login's required manifest, and only manifest JARs can be served. ServerProvider uses plain TCP: SHA-512 checks protect file integrity, but transport is **not encrypted**. The player must confirm the exact server file list before any ServerProvider connection. That confirmation applies only to the current session and exact SHA-512 file set; it is never remembered. A Modrinth lookup failure remains unresolved; an absent exact hash can be offered by ServerProvider. A failed server transfer cannot create a pending install. Public and server files share one staging workspace and one final update plan. Source selection is recalculated on each new sync session, so an exact JAR later added to Modrinth will use the public source on the next attempt.

The client never sends its complete mod list. A Jane server sends a login query; the client responds only with PASS, MISMATCH, or PROTOCOL_ERROR. On mismatch the login ends before the sync screen opens. Extra client mods do not block login. Server-provided URLs, paths, filenames, and commands are never accepted.

## Local files

Jane uses only the active instance's game directory. The client may create `jane/cache`, `jane/staging`, `jane/pending`, and `jane/backups` beside that instance's `mods` folder. A pending update contains `pending.json`, `backup.json`, and `update.bat`. The BAT logs within that pending directory. Jane never edits another Minecraft instance.

Automatic replacement requires Windows and conservative JAR filenames. If an existing or downloaded filename cannot be represented safely in CMD, Jane stops the automatic path and the player must install it manually.
