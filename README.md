# BiomePockets

First-pass Forge 1.18.2 mod for temporary, single-biome pocket dimensions.

## Items

- **Biome Transporter**: uses the vanilla blaze rod model. Right-clicking selects a random biome from the server biome registry, creates a new temporary dimension containing only that biome, prepares its playable area, and teleports the player once preparation is complete.
- **Biome Transporter Selector**: uses the vanilla bone model. Right-clicking opens a searchable biome list and creates a new pocket for the selected biome.

Biome discovery is registry-driven. There are no hard dependencies on Biomes O' Plenty or Oh The Biomes You'll Go; if their biomes are registered, they appear automatically alongside vanilla and other mod/datapack biomes.

For testing, `/biomepockets <item>` gives the executing player one of the mod items. Tab completion currently exposes `biome_transporter` and `biome_transporter_selector`.

`/biomepockets dimensions` reports both the currently loaded `ServerLevel`s and the registered `LevelStem`s, including vanilla and dimensions supplied by other mods.

## Pocket shape and preparation

Each pocket retains the normal 1.18.2 Overworld vertical range and terrain generation, but real terrain is restricted to exactly a 3x3 chunk square:

```text
000
000
000
```

The terrain chunks are `-1..1` on both X and Z, covering blocks `-16..31` on each horizontal axis.

All nine playable chunks are requested together through `ServerChunkCache.getChunkFuture(..., ChunkStatus.FULL, true)` and held with temporary preparation tickets. Minecraft's normal chunk/worldgen executors therefore perform terrain, surfaces, carvers, FEATURES, lighting, and final chunk conversion without serially blocking the main server thread with nine synchronous `getChunk(...FULL...)` calls.

The player remains in their current dimension while generation is in progress and is teleported only after all nine FULL futures have completed successfully. If the requester disconnects before ever entering the new pocket, the unused prepared pocket is torn down rather than left orphaned.

Minecraft's generation and view-distance systems may still request surrounding dependency chunks. BiomePockets uses a bounded noise generator so those outside chunks remain void: they do not receive terrain, surface generation, carvers, structures, or pocket biome decoration.

A full-height barrier-block wall is placed one block outside the 3x3 terrain footprint, at block coordinates `-17` and `32`. Barrier placement is performed from the bounded generator's FEATURES work for the edge chunks instead of issuing tens of thousands of `ServerLevel#setBlock` calls on the main server thread before teleport. This preserves all 48x48 terrain blocks while providing a physical collision boundary that players and mobs cannot cross.

## Biome features

The selected biome's own registered `BiomeGenerationSettings` are authoritative for pocket decoration. During the real `ChunkStatus.FEATURES` stage, each playable chunk reads that biome's `features()` list and runs each registered `PlacedFeature` with `placeWithBiomeCheck(...)`.

This deliberately bypasses `ChunkGenerator`/`BiomeSource`'s normal multi-biome `featuresPerStep` selection/indexing layer. A pocket is a fixed single-biome world, so there is no need to rebuild a global feature ordering from multiple possible biomes. The actual vanilla, BOP, BYG, or datapack `PlacedFeature` objects are still used unchanged, including their normal placement modifiers and biome filters.

Feature randomization follows vanilla decoration conventions: the origin is the chunk's minimum X/Z at Y=0, and the decoration seed is derived from the pocket generator's own terrain seed. The server log reports the selected biome's placed-feature count and, for the center chunk, how many features were attempted versus how many reported a successful placement. These diagnostics are intended to make any remaining runtime worldgen incompatibility immediately visible.

## Pocket lifecycle and deletion safety

Every use creates a fresh `biomepockets:pocket_<uuid>` dimension. A pocket is torn down only after the last connected player actually changes dimension out of it. Other players can enter it by command while it exists; it remains alive until the last player explicitly leaves the dimension.

Logging out is deliberately **not** treated as leaving the pocket. A normal logout, dropped connection, or client crash leaves the dimension intact, allowing vanilla player data to restore the player to the same pocket and coordinates when they reconnect while the server remains running.

Full server-process persistence is not implemented yet. On orderly server shutdown, players still inside active pockets are returned to the overworld before those pockets are removed. On the next orderly start after a server crash, only marker-validated stale `biomepockets:pocket_*` dimensions are reclaimed. Persistent pockets across a complete server restart require serialization/recovery of the runtime bounded generator and are a separate feature.

Disk deletion has deliberately redundant ownership checks. A directory is removed only when all of these are true:

1. The dimension is in BiomePockets' in-memory ownership map, or is being recovered as a stale pocket at server start.
2. Its namespace is exactly `biomepockets` and its path begins with `pocket_`.
3. Its storage path is beneath the world's `dimensions/biomepockets/` directory and its final directory name exactly matches the dimension path.
4. The directory contains `.biomepockets-owned` whose contents exactly match the dimension ID.

Vanilla dimensions and dimensions created by other mods are never candidates for teardown.

## First-pass behavior

Pocket terrain uses Overworld noise generation with a `FixedBiomeSource` for the selected biome. The bounded generator delegates normal terrain generation only for the nine pocket chunks and runs that selected biome's registered placed features during the actual FEATURES stage. This is intentionally generic and lets vanilla, BOP, BYG, and datapack biomes work without compile-time integration. Biomes designed specifically around Nether/End terrain may therefore look unusual when paired with Overworld noise settings.

There is not yet a dedicated return item/portal. Leaving by command or any other dimension-changing mechanic triggers the normal empty-pocket teardown. Disconnecting does not.

## Build

Java 17, Minecraft 1.18.2, Forge 40.3.12.

```text
gradle build
```

GitHub Actions is push-only and follows the same numbered-artifact pattern used by MCMoltenMetals: each branch build uploads a `biome-pockets-<branch-commit-number>.jar` artifact.
