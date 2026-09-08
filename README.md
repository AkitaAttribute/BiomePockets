# BiomePockets

First-pass Forge 1.18.2 mod for temporary, single-biome pocket dimensions.

## Items

- **Biome Transporter**: uses the vanilla blaze rod model. Right-clicking selects a random biome from the server biome registry, creates a new temporary dimension containing only that biome, and teleports the player into it.
- **Biome Transporter Selector**: uses the vanilla bone model. Right-clicking opens a searchable biome list and creates a new pocket for the selected biome.

Biome discovery is registry-driven. There are no hard dependencies on Biomes O' Plenty or Oh The Biomes You'll Go; if their biomes are registered, they appear automatically alongside vanilla and other mod/datapack biomes.

For testing, `/biomepockets <item>` gives the executing player one of the mod items. Tab completion currently exposes `biome_transporter` and `biome_transporter_selector`.

## Pocket shape

Each pocket retains the normal 1.18.2 Overworld vertical range and terrain generation, but real terrain is restricted to exactly a 3x3 chunk square:

```text
000
000
000
```

The terrain chunks are `-1..1` on both X and Z, covering blocks `-16..31` on each horizontal axis. The nine chunks are first generated through `ChunkStatus.FULL` for the normal terrain/surface/carver pipeline.

Biome population is then run explicitly once for each of those nine chunks through vanilla 1.18.2's `NoiseBasedChunkGenerator.applyBiomeDecoration(...)` path. This is the standard placed-feature routine responsible for trees, grass, flowers, ores, springs, patches, and other biome decoration. The bounded generator deliberately suppresses its normal FEATURES decoration hook so a pocket chunk cannot be populated twice.

Minecraft's generation and view-distance systems may still request surrounding dependency chunks. BiomePockets uses a bounded noise generator so those outside chunks remain void: they do not receive terrain, surface generation, carvers, structures, or biome decoration.

A full-height barrier-block wall is placed one block outside the 3x3 terrain footprint, at block coordinates `-17` and `32`. This gives the player all 48x48 terrain blocks while providing a physical collision boundary that players and mobs cannot cross. The previous per-pocket world-border approach was removed.

## Pocket lifecycle and deletion safety

Every use creates a fresh `biomepockets:pocket_<uuid>` dimension. A pocket is unloaded after the last player actually changes dimension out of it. Other players can enter it by command while it exists; it remains alive until the last one leaves.

Disk deletion has deliberately redundant ownership checks. A directory is removed only when all of these are true:

1. The dimension is in BiomePockets' in-memory ownership map, or is being recovered as a stale pocket at server start.
2. Its namespace is exactly `biomepockets` and its path begins with `pocket_`.
3. Its storage path is beneath the world's `dimensions/biomepockets/` directory and its final directory name exactly matches the dimension path.
4. The directory contains `.biomepockets-owned` whose contents exactly match the dimension ID.

Vanilla dimensions and dimensions created by other mods are never candidates for teardown.

On orderly server shutdown, players still inside active pockets are returned to the overworld before those pockets are removed. On the next orderly start after a crash, only marker-validated stale `biomepockets:pocket_*` dimensions are reclaimed.

## First-pass behavior

Pocket terrain uses Overworld noise generation with a `FixedBiomeSource` for the selected biome. The bounded generator delegates normal terrain generation only for the nine pocket chunks, while biome population is performed in a separate explicit vanilla decoration pass after those chunks exist. This is intentionally generic and lets vanilla, BOP, BYG, and datapack biomes work without compile-time integration. Biomes designed specifically around Nether/End terrain may therefore look unusual.

There is not yet a dedicated return item/portal. Leaving by command or any other dimension-changing mechanic triggers the normal empty-pocket teardown.

## Build

Java 17, Minecraft 1.18.2, Forge 40.3.12.

```text
gradle build
```

GitHub Actions is push-only and follows the same numbered-artifact pattern used by MCMoltenMetals: each branch build uploads a `biome-pockets-<branch-commit-number>.jar` artifact.
