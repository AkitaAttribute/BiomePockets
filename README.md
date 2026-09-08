# BiomePockets

First-pass Forge 1.18.2 mod for temporary, single-biome pocket dimensions.

## Items

- **Biome Transporter**: uses the vanilla blaze rod model. Right-clicking selects a random biome from the server biome registry, creates a new temporary dimension containing only that biome, and teleports the player into it.
- **Biome Transporter Selector**: uses the vanilla bone model. Right-clicking opens a searchable biome list and creates a new pocket for the selected biome.

Biome discovery is registry-driven. There are no hard dependencies on Biomes O' Plenty or Oh The Biomes You'll Go; if their biomes are registered, they appear automatically alongside vanilla and other mod/datapack biomes.

## Pocket shape

Each pocket retains the normal 1.18.2 Overworld vertical range and terrain generation, but the playable footprint is exactly a 3x3 chunk square:

```text
000
000
000
```

The playable chunks are `-1..1` on both X and Z. A per-pocket world border is centered at block `(8, 8)` and set to 48 blocks wide, placing its edges exactly on the outer chunk boundaries at `-16` and `32`. The nine playable chunks are generated before the player is teleported in.

The pocket border is independent of the overworld border and is not synchronized to it.

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

Pocket terrain uses the normal Overworld noise generator with a `FixedBiomeSource` for the selected biome. This is intentionally generic and lets vanilla, BOP, BYG, and datapack biomes work without compile-time integration. Biomes designed specifically around Nether/End terrain may therefore look unusual.

There is not yet a dedicated return item/portal. Leaving by command or any other dimension-changing mechanic triggers the normal empty-pocket teardown.

## Build

Java 17, Minecraft 1.18.2, Forge 40.3.12.

```text
gradle build
```

GitHub Actions is push-only and follows the same numbered-artifact pattern used by MCMoltenMetals: each branch build uploads a `biome-pockets-<branch-commit-number>.jar` artifact.
