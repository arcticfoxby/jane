---

# 简（Jane）1.1.8 Beta

Jane targets Minecraft Java Edition 1.20.1, Fabric, and Java 17. Install a compatible Jane version on both client and server. Build with `gradlew.bat build`; use `build/libs/jane-1.1.8-beta.jar` rather than a development JAR.

## Selective Mod Sync

The server continues to discover direct physical JARs in its `mods/` folder and supplies each required file's version, size, and SHA-512. Required files that the client has not matched are selected for installation by default. An exact-hash Modrinth project with `client_side=optional` can be shown as an optional installation suggestion, initially unselected. This is metadata-based advice, not a server declaration that the file is safe to omit. Unverified or unavailable metadata keeps the file in the server-required group.

Players may deselect a required file or select an optional suggestion. Jane downloads only the selected files that are still missing or mismatched, verifies their size and SHA-512, and stages them before the Windows updater changes `mods/`. Selected updates require the usual close, install, and manual restart flow. Skipping a file never makes its physical comparison pass. On the next connection, an unsatisfied required file appears again and is selected by default. Jane does not permanently remember skip authorization.

Jane records the choice in Minecraft's `logs/latest.log` and in a bounded local `jane/logs/sync-decisions.log`. It does not send the client's complete mod list or checkbox selections to the server. Minecraft, Fabric, and other mods may still reject a connection or malfunction when a needed file is omitted.

## User Override Boundary

When a player chooses to try joining without installing an unsatisfied required file, Jane presents a risk confirmation. A confirmed override is limited to one reconnect with the same server and manifest, and the client checks its files again before using it. Jane reports that attempt as `USER_OVERRIDE`, never as an exact match. The server's Jane gate may allow the attempt, while Fabric and other compatibility checks continue normally. Jane verifies files it downloads, but cannot guarantee a working game after the player skips a needed file.

## Protocol Compatibility

Jane V1.1.8 Beta uses **Protocol 4**. Client and server Jane versions must be compatible; Protocol 3 is not silently downgraded. Protocol 4 distinguishes `EXACT_PASS`, `ACTION_REQUIRED`, `PROTOCOL_ERROR`, and `USER_OVERRIDE` without sending a client's private mod inventory.
