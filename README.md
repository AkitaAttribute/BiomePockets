# BiomePockets

First-pass Forge 1.18.2 mod for temporary, single-biome pocket dimensions.

## Items

- **Biome Transporter**: uses the vanilla blaze rod model. Right-clicking selects a random biome from the server biome registry, creates a new temporary dimension containing only that biome, and teleports the player into it.
- **Biome Transporter Selector**: uses the vanilla bone model. Right-clicking opens a searchable biome list and creates a new pocket for the selected biome.

Biome discovery is registry-driven. There are no hard dependencies on Biomes O' Plenty or Oh The Biomes You'll Go; if their biomes are registered, they appear automatically alongside vanilla and other mod/datapack biomes.

For testing, `/biomepockets <item>` gives the executing player one of the mod items. Tab completion currently exposes `biome_transporter` and `biome_transporter_selector`.

`/biomepockets dimensions` reports both the currently loaded `ServerLevel`s and the registered `LevelStem`s, including vanilla and dimensions supplied by other mods.

## Pocket shape

Each pocket contains a 3x3 playable terrain square:

```text
000
000
000
```

The terrain chunks are `-1..1` on both X and Z, covering blocks `-16..31` on each horizontal axis.

The complete one-chunk ring surrounding that playable area is generated as solid barrier chunks. In chunk coordinates, the prepared containment square is therefore `-2..2`, with the inner 3x3 using normal terrain and the outer 16 chunks consisting entirely of barrier blocks from build bottom to build top. This prevents trees and other placed features in edge chunks from blooming into neighboring void chunks.

The barrier ring is written directly into `ChunkAccess` during the ring chunks' NOISE stage rather than through live-world `ServerLevel#setBlock` calls. The nine playable chunks are still the chunks explicitly awaited through `ChunkStatus.FULL`; Minecraft's generation dependency graph prepares their neighboring ring chunks before edge FEATURES can complete, so the barrier exists before vegetation is placed without unnecessarily finalizing all 16 barrier chunks to FULL.

Chunks outside the 5x5 containment square remain void.

## Biome generation

Pocket terrain uses the selected biome's dimension family where possible. End-tagged biomes use End noise settings/type, Nether-tagged biomes use Nether settings/type, and other biomes use Overworld settings/type. Forge/vanilla biome tags are used first with the 1.18.2 `BiomeDictionary` as a compatibility fallback.

Biome population runs during the real FEATURES stage. Because a pocket has one exact fixed biome, the bounded generator executes that biome's own `BiomeGenerationSettings.features()` / registered `PlacedFeature`s directly while preserving placement modifiers and biome checks. This has been runtime-validated for vanilla trees, grass, flowers, and mushrooms.

## Asynchronous preparation

All nine playable chunks are submitted together with `ServerChunkCache.getChunkFuture(..., ChunkStatus.FULL, true)` and temporary region tickets. The player remains in the source dimension while terrain, surfaces, carvers, FEATURES, lighting, and final chunk conversion finish. Teleport occurs only after all nine playable chunks complete successfully.

Before teleport, BiomePockets searches the full playable area for a safe location with a sturdy floor plus collision-free, fluid-free feet and head blocks. If no natural safe position exists, it creates a 3x3 obsidian emergency platform and clears two blocks of player space above it.

## Selector

The selector supports normal text search and namespace search. Prefixing a query with `#` filters by mod namespace, for example `#minecraft`.

A separate Mods tab lists namespaces with biome counts. Selecting a namespace shows only its biomes; the Mods tab then acts as a back control to the namespace list.

## Pocket lifecycle and deletion safety

Every use creates a fresh `biomepockets:pocket_<uuid>` dimension. A pocket is torn down only after the last connected player actually changes dimension out of it.

Logging out, losing the connection, or crashing the client is deliberately not treated as leaving. While the server remains running, BiomePockets records the disconnected player's pocket dimension, coordinates, and rotation and explicitly restores them there on login. A pending return reservation also keeps the pocket alive if another player leaves while the disconnected player is expected to return.

Full server-process persistence is not implemented yet. The runtime bounded generator is not serialized, so orderly shutdown/startup cleanup remains separate.

Disk deletion has deliberately redundant ownership checks. A directory is removed only when all of these are true:

1. The dimension is in BiomePockets' in-memory ownership map, or is being recovered as a stale pocket at server start.
2. Its namespace is exactly `biomepockets` and its path begins with `pocket_`.
3. Its storage path is beneath the world's `dimensions/biomepockets/` directory and its final directory name exactly matches the dimension path.
4. The directory contains `.biomepockets-owned` whose contents exactly match the dimension ID.

Vanilla dimensions and dimensions created by other mods are never candidates for teardown.

## Xaero compatibility

BiomePockets emits normal Forge world load/unload events and uses unique dimension IDs. No private Xaero API hook is used. Xaero World Map's Server map-selection mode, with Xaero installed server-side as well as client-side, is the recommended compatibility configuration for dynamic level IDs.

## Build

Java 17, Minecraft 1.18.2, Forge 40.3.12.

```text
gradle build
```

GitHub Actions is push-only and follows the same numbered-artifact pattern used by MCMoltenMetals: each branch build uploads a `biome-pockets-<branch-commit-number>.jar` artifact.
