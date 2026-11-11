---

# 简（Jane）1.1.8.5 Beta

Jane targets Minecraft Java Edition 1.20.1, Fabric, and Java 17. Install a compatible Jane version on both client and server. Build with `gradlew.bat build`; use `build/libs/jane-1.1.8.5-beta.jar` rather than a development JAR.

## Connection decision

On each connection, Jane validates the server's Required Manifest and compares it with the client's physical `mods/*.jar` files. If all Required files match by Mod ID, version, and SHA-512 and there are no extra client mods, Jane reports `EXACT_PASS` and continues immediately. If Required files are missing or mismatched, or if extra client mods are present, Jane shows a compact environment decision screen. The player can choose to synchronize or to review the risks and attempt a direct join. This local comparison does not query Modrinth.

Choosing **Synchronize Mod Environment** opens the classic sync screen and starts source resolution. Jane only contacts Modrinth after this choice. If all Required files already match and only extra client mods remain, synchronization opens the client compatibility review instead; it does not download Required files or automatically remove extras. Explicit client-only and other extra mods are reported separately. Extra mods remain installed unless the player explicitly selects an item for the existing disable helper.

## Manual extra-Mod disable

Jane strictly verifies the server's Required files; extra client mods are outside that baseline and are not necessarily unnecessary. The compatibility review keeps explicitly client-only mods and other universal-environment mods in separate groups. Neither group is selected by default. A player may manually choose files from either group, including a mix, but Jane never automatically disables universal mods or their dependencies.

Disabling a universal mod may break another mod's dependency, prevent startup, or cause missing features or crashes. Selecting one requires an additional risk confirmation. Before creating a disable plan, Jane rechecks the chosen physical JARs in this game's direct `mods/` directory and verifies their SHA-512; universal extras are hashed only after selection. Required files and Jane itself cannot enter this plan. The existing CMD helper waits for Minecraft to exit, then moves only the verified selected files into `jane/disabled/<serverId>/<timestamp>/` and writes `disabled.json`, `disable.log`, and a result marker. It keeps backups and the existing failure rollback; it does not permanently delete those JARs. If needed, close Minecraft and manually move a backed-up JAR into `mods/` to restore it.

Jane records the player's choice, risk confirmation, prepared plan, and helper launch in local audit and Minecraft logs. A prepared plan or launched helper is not proof that the CMD operation succeeded; inspect `disable.log` and its result marker. No complete client mod inventory is uploaded to the server.

Choosing **Join Server Directly** opens a separate risk confirmation with the unmet Required files and extra client mods. An extra-only confirmation preserves those mods and uses a one-time local reconnect permission; the next login still reports the genuine `EXACT_PASS` because the Required baseline matches. A missing or mismatched Required file instead uses one-time Protocol 4 `USER_OVERRIDE`. That attempt does not mean the files match, and Minecraft, Fabric, or the server may still refuse the connection. File errors and unsafe comparisons cannot be bypassed this way. An independent later connection performs the assessment again.

## Server transfer blacklist

On a dedicated server, Jane creates `jane/blacklist/` beside `mods/` when it prepares the Required Manifest. To exclude a server JAR from client synchronization, copy that JAR from `mods/` into `jane/blacklist/` with the same filename, then restart the server. Jane excludes it from both the Required Manifest and its server download provider only when the two direct-child files have the same size and SHA-512. A JAR found only in `jane/blacklist/` is ignored. If the server's JAR changes but its blacklist copy does not, the updated JAR is synchronized normally. The local `config/jane/discovered-mods.json` diagnostic records exclusions with reason `blacklist_exact_copy`.

The blacklist does not unload a server mod or change Fabric's dependency checks. A client may still need an excluded mod to join or play correctly.

## Selective Mod Sync

The server continues to discover direct physical JARs in its `mods/` folder and supplies each required file's version, size, and SHA-512. Required files that the client has not matched are selected for installation by default. An exact-hash Modrinth project with `client_side=optional` can be shown as a secondary installation suggestion, initially selected but adjustable by the player. This is metadata-based advice, not proof of what the server actually needs. Unverified or unavailable metadata keeps the file in the server-required group; damaged or unsafe local files are errors rather than optional download candidates.

Jane downloads only the selected files that are still missing or mismatched, verifies their size and SHA-512, and stages them before the Windows updater changes `mods/`. Selected updates require the usual close, install, and manual restart flow. Deselecting an optional suggestion that is still in the Required Manifest leaves the Required comparison unsatisfied; Jane never reports it as `EXACT_PASS`. Players who want to skip synchronization use the preceding direct-join decision and risk confirmation. Jane does not permanently remember skip authorization.

Jane records the choice in Minecraft's `logs/latest.log` and in a bounded local `jane/logs/sync-decisions.log`. It does not send the client's complete mod list or checkbox selections to the server. Minecraft, Fabric, and other mods may still reject a connection or malfunction when a needed file is omitted.

## User Override Boundary

When a player confirms joining without installing an unsatisfied required file, Jane logs each skipped file's Mod ID, target version, comparison status, and short hash prefix. The confirmed override is limited to one reconnect with the same server, manifest, and Required-file assessment; the client checks its files again before using it. Jane reports that attempt as `USER_OVERRIDE`, never as an exact match. The server's Jane gate may allow the attempt, while Fabric and other compatibility checks continue normally. Jane verifies files it downloads, but cannot guarantee a working game after the player skips a needed file. This does not waive responsibility for defects in Jane itself.

## Protocol Compatibility

Jane V1.1.8.5 Beta uses **Protocol 4**. Client and server Jane versions must be compatible; Protocol 3 is not silently downgraded. Protocol 4 distinguishes `EXACT_PASS`, `ACTION_REQUIRED`, `PROTOCOL_ERROR`, and `USER_OVERRIDE` without sending a client's private mod inventory.
