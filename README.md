# GeyserPlayerHeads

Geyser extension that registers your players' skins as custom skulls, so Bedrock players see player heads with the right face in inventories, in hand and when worn. Without it, Geyser shows a plain Steve head there.

Each time Geyser starts, the extension:

1. reads the players in the server's `usercache.json` (everyone who has joined recently, most recent first, up to 200)
2. looks up each one's current skin: Java players through Mojang's session server, Floodgate players through the GeyserMC skin API
3. registers those skins, plus every skin it registered at an earlier start, so heads made before a skin change keep their face

It logs `Registered 12 player skins as custom skulls (looked up 9 of 9 players)`. Lookups run in parallel and give up after 20 seconds, so a slow API can't hold up the server for long. Skins that fail to look up stay registered from earlier starts.

## Limits

- Geyser only accepts custom skulls while it starts. A new player's head, or a head with a new skin, shows to Bedrock players after the next restart.
- Offline-mode players have no skin to look up and are skipped.

## Install

1. Put `GeyserPlayerHeads-1.0.jar` from [Releases](https://github.com/lzlrd/GeyserPlayerHeads/releases) in Geyser's `extensions/` folder (`plugins/Geyser-Spigot/extensions/` on Paper).
2. Restart the server.

Needs Geyser's extension API 2.9.0 or later; tested on Geyser-Spigot 2.12.1. On Geyser Standalone or a proxy there is no `usercache.json` next to Geyser, so point `user-cache` at the backend server's.

## Configure

`extensions/geyserplayerheads/config.properties` is written on first start:

| Key | Default | Purpose |
| --- | --- | --- |
| `user-cache` | `usercache.json` | Player list, relative to the working directory (the server folder on Paper) |
| `java-profile-url` | `https://sessionserver.mojang.com/session/minecraft/profile/` | Java skin lookups |
| `bedrock-skin-url` | `https://api.geysermc.org/v2/skin/` | Bedrock skin lookups |

`skins.txt` beside it lists every registered skin as `uuid skin-hash`. Delete a line to drop that skin.

## Build

```sh
./gradlew build
```

Needs JDK 25 (the jar targets Java 21, like Geyser). Writes `build/libs/GeyserPlayerHeads-1.0.jar`.

## Licence

[PolyForm Noncommercial 1.0.0](LICENSE.md).
