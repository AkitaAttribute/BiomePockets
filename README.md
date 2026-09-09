# BiomePockets

Forge 1.18.2 prototype for temporary, registry-driven single-biome pocket dimensions.

## Current behavior

- Discovers registered biomes from the live biome registry, including vanilla, Biomes O' Plenty, BYG/other mods, and datapacks.
- Provides a random Biome Transporter and searchable Biome Transporter Selector.
- Selector supports normal biome search, `#namespace` filtering, and a Mods tab grouped by namespace.
- Creates one runtime dimension per transporter use using a fixed selected biome.
- Uses Overworld, Nether, or End generation/environment profiles according to biome tags/classification.
- Generates only the playable 3x3 terrain square (`-1..1` chunks).
- Surrounds that terrain with a complete one-chunk-thick solid barrier ring (`-2..2` outer ring); chunks beyond remain void.
- Generates all nine playable chunks asynchronously through FULL before teleporting the player.
- Runs the selected biome's registered placed features during FEATURES; trees, grass, flowers, mushrooms, and other biome decoration have been runtime validated.
- Uses profile-aware initial spawning: highest center surface for normal worlds, cave preference for underground biomes, and safe Nether opening/protected chamber logic.
- End-profile pockets retain End terrain/fog/material behavior but suppress the vanilla End dragon fight controller and boss-arena features.

## Pocket lifetime and persistence

Pocket teardown is tied to an explicit dimension departure, not connection state or server process lifetime.

- Disconnect/logout does not destroy the pocket.
- A connected-server reconnect restores the player to the exact saved pocket position.
- Save & Quit / orderly dedicated-server shutdown preserves the pocket dimension folder and player return point instead of teleporting players to Overworld or deleting the pocket.
- On the next server start, BiomePockets reconstructs each marker-validated pocket with the bounded runtime generator before player login restoration.
- If a dynamic LevelStem was autosaved before a crash, recovery replaces that automatically loaded level with the bounded BiomePockets generator rather than trusting the serialized superclass generator.
- Pocket metadata is written when players enter/disconnect and again on orderly shutdown so existing chunk data can be reused without regenerating the 3x3.

Normal explicit departure from the pocket still tears it down when no players or return reservations remain.

The restart/recovery implementation compiles successfully but still requires runtime validation in both integrated-server Save & Quit and dedicated-server restart scenarios.

## Deletion guardrails

Destructive cleanup remains restricted to dimensions that:

1. are in BiomePockets runtime ownership state,
2. use namespace exactly `biomepockets`,
3. have a path beginning `pocket_`,
4. have a `.biomepockets-owned` marker whose exact contents match the dimension ID, and
5. resolve to the expected folder beneath `dimensions/biomepockets/`.

Vanilla and third-party dimensions are never selected for deletion based on emptiness.

## Diagnostic command

`/biomepockets dimensions` reports loaded ServerLevels and registered LevelStems, including vanilla and third-party dimensions.
