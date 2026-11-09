# GeyserPlayerHeads

Geyser extension that registers your players' skins as custom skulls, so Bedrock players see player heads with the right face in inventories, in hand and when worn. Without it, Geyser shows a plain Steve head there. It can also let thousands of head skins, such as a mob heads datapack's variants, share a few custom skulls ([Skull aliases](#skull-aliases)).

Each time Geyser starts, the extension:

1. reads the players in the server's `usercache.json` (everyone who has joined recently, most recent first, up to 200)
2. looks up each one's current skin: Java players through Mojang's session server, Floodgate players through the GeyserMC skin API
3. registers those skins, plus every skin it registered at an earlier start, so heads made before a skin change keep their face

It logs `Registered 12 player skins as custom skulls (looked up 9 of 9 players)`. Lookups run in parallel and give up after 20 seconds, so a slow API can't hold up the server for long. Skins that fail to look up stay registered from earlier starts.

## Skull aliases

Each custom skull costs Geyser about 240 KB of heap, so registering every variant of a mob heads datapack (over 4,000 skins) takes a gigabyte. `extensions/geyserplayerheads/skull-aliases.txt` registers a few skulls and points the other skins at them:

```text
# A line with one skin hash registers that skin as a custom skull.
dc4b5f6d75126380f520a67ca57bc9a56aa11db8afe7e5dcb2a52dfcfeae0785
# "skin target" shows the target's skull as the item icon of heads with the first skin.
ad068885d2dfa3a309363af0a87dca6fbf629f713074f9686219d7ba7af7008d dc4b5f6d75126380f520a67ca57bc9a56aa11db8afe7e5dcb2a52dfcfeae0785
```

- Item icons use the target's skull: in inventories, in hand, worn, in item frames and dropped. The item keeps its own name.
- Placed heads keep their own skin. Geyser draws a head that isn't a registered skull as a skull entity, up to `max-visible-custom-skulls` (128 by default) within `custom-skull-render-distance` (32 blocks) of each player.
- The extension registers every target and single-hash line, so leave them out of Geyser's `custom_mappings`.

It logs `Registered 86 skulls from skull-aliases.txt as custom skulls, with 4131 more head skins aliased to them`, then once Geyser has started `Showing 4131 head skins with 83 custom skulls in item icons`. [GeyserPacks](https://github.com/lzlrd/GeyserPacks) builds this file for Jodek's Mob Heads, one skull per mob; in testing that cut the server's live heap from 1.6 GB to 0.6 GB.

Geyser has no API for this, so the aliases wrap Geyser's internal custom skull registry. If a Geyser update moves it, the extension logs a warning and aliased heads show as plain player heads until it's updated.

## Limits

- Geyser only accepts custom skulls while it starts. A new player's head, a head with a new skin, or a change to `skull-aliases.txt` shows to Bedrock players after the next restart.
- Offline-mode players have no skin to look up and are skipped.

## Install

1. Put `GeyserPlayerHeads-1.1.jar` from [Releases](https://github.com/lzlrd/GeyserPlayerHeads/releases) in Geyser's `extensions/` folder (`plugins/Geyser-Spigot/extensions/` on Paper), replacing any older version.
2. Restart the server.

Needs Geyser's extension API 2.9.0 or later; tested on Geyser-Spigot 2.12.1. On Geyser Standalone or a proxy there is no `usercache.json` next to Geyser, so point `user-cache` at the backend server's.

## Configure

`extensions/geyserplayerheads/config.properties` is written on first start:

| Key | Default | Purpose |
| --- | --- | --- |
| `user-cache` | `usercache.json` | Player list, relative to the working directory (the server folder on Paper) |
| `java-profile-url` | `https://sessionserver.mojang.com/session/minecraft/profile/` | Java skin lookups |
| `bedrock-skin-url` | `https://api.geysermc.org/v2/skin/` | Bedrock skin lookups |

`skins.txt` beside it lists every registered player skin as `uuid skin-hash`. Delete a line to drop that skin. `skull-aliases.txt` is written empty on first start.

## Build

```sh
./gradlew build
```

Needs JDK 25 (the jar targets Java 21, like Geyser). Writes `build/libs/GeyserPlayerHeads-1.1.jar`.

## Licence

[PolyForm Noncommercial 1.0.0](LICENSE.md).
