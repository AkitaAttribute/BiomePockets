package com.akitaattribute.biomepockets.world;

import com.akitaattribute.biomepockets.BiomePockets;
import com.google.common.collect.ImmutableList;
import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Lifecycle;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.WritableRegistry;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.WorldData;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Stream;

public final class PocketDimensionManager {
    private static final String POCKET_PREFIX = "pocket_";
    private static final String MARKER_FILE = ".biomepockets-owned";

    // Real terrain exists only in chunks -1..1 in both axes.
    private static final int MIN_POCKET_CHUNK = -1;
    private static final int MAX_POCKET_CHUNK = 1;

    // Keep the nine requested chunks resident while their asynchronous FULL futures
    // progress through terrain, FEATURES, lighting, and final chunk conversion.
    private static final TicketType<ResourceLocation> PREPARATION_TICKET = TicketType.create(
            "biomepockets_prepare",
            Comparator.comparing(ResourceLocation::toString));
    private static final int PREPARATION_TICKET_RADIUS = 1;

    private static final Map<ResourceKey<Level>, PocketRecord> OWNED = new HashMap<>();

    private PocketDimensionManager() { }

    public static void createAndTeleport(ServerPlayer player, ResourceLocation biomeId) {
        MinecraftServer server = player.getServer();
        Optional<Holder<Biome>> biome = BiomeCatalog.getBiome(server, biomeId);
        if (biome.isEmpty()) {
            return;
        }

        ResourceLocation dimensionId = new ResourceLocation(BiomePockets.MOD_ID,
                POCKET_PREFIX + UUID.randomUUID().toString().replace("-", ""));
        ResourceKey<Level> levelKey = ResourceKey.create(Registry.DIMENSION_REGISTRY, dimensionId);

        try {
            ServerLevel pocket = createLevel(server, levelKey, biome.get());
            Path folder = pocketFolder(server, levelKey);
            PocketRecord record = new PocketRecord(biomeId, folder);
            OWNED.put(levelKey, record);
            writeOwnershipMarker(levelKey, folder);

            player.displayClientMessage(
                    new TextComponent("Preparing " + biomeId + " biome pocket..."),
                    true);
            preparePocketAsync(server, pocket, levelKey, record, player.getUUID());
        } catch (Exception exception) {
            BiomePockets.LOGGER.error("Unable to create biome pocket for {}", biomeId, exception);
        }
    }

    private static void preparePocketAsync(
            MinecraftServer server,
            ServerLevel pocket,
            ResourceKey<Level> levelKey,
            PocketRecord record,
            UUID playerId) {
        ServerChunkCache chunkSource = pocket.getChunkSource();
        ResourceLocation ticketOwner = levelKey.location();
        List<CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> futures = new ArrayList<>(9);

        // Submit all nine requests before waiting for any of them. getChunkFuture lets
        // Minecraft's normal chunk/worldgen executors perform the expensive pipeline
        // instead of serially blocking the server thread with getChunk(...FULL...).
        for (int chunkX = MIN_POCKET_CHUNK; chunkX <= MAX_POCKET_CHUNK; chunkX++) {
            for (int chunkZ = MIN_POCKET_CHUNK; chunkZ <= MAX_POCKET_CHUNK; chunkZ++) {
                ChunkPos pos = new ChunkPos(chunkX, chunkZ);
                chunkSource.addRegionTicket(
                        PREPARATION_TICKET,
                        pos,
                        PREPARATION_TICKET_RADIUS,
                        ticketOwner);
                futures.add(chunkSource.getChunkFuture(chunkX, chunkZ, ChunkStatus.FULL, true));
            }
        }

        CompletableFuture<Void> allChunks = CompletableFuture.allOf(
                futures.toArray(new CompletableFuture<?>[0]));

        allChunks.whenComplete((ignored, throwable) -> server.execute(() -> {
            if (server.getLevel(levelKey) != pocket || OWNED.get(levelKey) != record) {
                return;
            }

            if (throwable != null || !allChunksReachedFull(futures)) {
                BiomePockets.LOGGER.error(
                        "Pocket {} failed to finish all nine FULL chunks",
                        levelKey.location(),
                        throwable);
                removePreparationTickets(pocket, ticketOwner);

                ServerPlayer currentPlayer = server.getPlayerList().getPlayer(playerId);
                if (currentPlayer != null) {
                    currentPlayer.displayClientMessage(
                            new TextComponent("Biome pocket generation failed. See server log."),
                            false);
                }
                teardown(server, levelKey, record, false);
                return;
            }

            ServerPlayer currentPlayer = server.getPlayerList().getPlayer(playerId);
            if (currentPlayer == null) {
                // The requester never entered this pocket, so there is no progress to
                // preserve. Do not leave an unreachable prepared dimension behind.
                removePreparationTickets(pocket, ticketOwner);
                teardown(server, levelKey, record, false);
                return;
            }

            // FULL completion means the nine terrain chunks have passed FEATURES and
            // lighting. The bounded generator also builds the barrier during FEATURES,
            // so nothing remains to generate synchronously before teleport.
            int y = pocket.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 0, 0);
            y = Math.max(y + 1, pocket.getMinBuildHeight() + 2);
            currentPlayer.teleportTo(
                    pocket,
                    0.5D,
                    y,
                    0.5D,
                    currentPlayer.getYRot(),
                    currentPlayer.getXRot());

            removePreparationTickets(pocket, ticketOwner);
        }));
    }

    private static boolean allChunksReachedFull(
            List<CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> futures) {
        for (CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> future : futures) {
            Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure> result = future.getNow(null);
            if (result == null || result.left().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static void removePreparationTickets(ServerLevel pocket, ResourceLocation ticketOwner) {
        ServerChunkCache chunkSource = pocket.getChunkSource();
        for (int chunkX = MIN_POCKET_CHUNK; chunkX <= MAX_POCKET_CHUNK; chunkX++) {
            for (int chunkZ = MIN_POCKET_CHUNK; chunkZ <= MAX_POCKET_CHUNK; chunkZ++) {
                chunkSource.removeRegionTicket(
                        PREPARATION_TICKET,
                        new ChunkPos(chunkX, chunkZ),
                        PREPARATION_TICKET_RADIUS,
                        ticketOwner);
            }
        }
    }

    public static boolean isOwned(ResourceKey<Level> key) {
        return OWNED.containsKey(key) && isPocketKey(key);
    }

    public static void handleDeparture(MinecraftServer server, ResourceKey<Level> from) {
        if (!isOwned(from)) {
            return;
        }
        server.execute(() -> teardownIfEmpty(server, from));
    }

    public static void teardownIfEmpty(MinecraftServer server, ResourceKey<Level> key) {
        PocketRecord record = OWNED.get(key);
        if (record == null || !isPocketKey(key)) {
            return;
        }
        ServerLevel level = server.getLevel(key);
        if (level == null) {
            OWNED.remove(key);
            return;
        }
        if (!level.players().isEmpty()) {
            return;
        }
        teardown(server, key, record, false);
    }

    public static void cleanupStalePockets(MinecraftServer server) {
        List<ResourceKey<Level>> keys = new ArrayList<>();
        @SuppressWarnings("deprecation")
        Map<ResourceKey<Level>, ServerLevel> worlds = server.forgeGetWorldMap();
        for (ResourceKey<Level> key : new ArrayList<>(worlds.keySet())) {
            if (isPocketKey(key)) {
                Path folder = pocketFolder(server, key);
                if (hasValidOwnershipMarker(key, folder)) {
                    OWNED.put(key, new PocketRecord(null, folder));
                    keys.add(key);
                }
            }
        }
        for (ResourceKey<Level> key : keys) {
            PocketRecord record = OWNED.get(key);
            if (record != null) {
                teardown(server, key, record, true);
            }
        }
    }

    public static void shutdown(MinecraftServer server) {
        for (ResourceKey<Level> key : new ArrayList<>(OWNED.keySet())) {
            PocketRecord record = OWNED.get(key);
            if (record != null && isPocketKey(key)) {
                teardown(server, key, record, true);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private static ServerLevel createLevel(MinecraftServer server, ResourceKey<Level> levelKey, Holder<Biome> biome) {
        Map<ResourceKey<Level>, ServerLevel> worlds = server.forgeGetWorldMap();
        if (worlds.containsKey(levelKey)) {
            throw new IllegalStateException("Pocket dimension already exists: " + levelKey.location());
        }

        Registry<StructureSet> structureSets = server.registryAccess().registryOrThrow(Registry.STRUCTURE_SET_REGISTRY);
        Registry<NormalNoise.NoiseParameters> noiseParameters = server.registryAccess().registryOrThrow(Registry.NOISE_REGISTRY);
        Registry<NoiseGeneratorSettings> noiseSettings = server.registryAccess().registryOrThrow(Registry.NOISE_GENERATOR_SETTINGS_REGISTRY);
        Registry<DimensionType> dimensionTypes = server.registryAccess().registryOrThrow(Registry.DIMENSION_TYPE_REGISTRY);

        long seed = server.getWorldData().worldGenSettings().seed() ^ UUID.randomUUID().getMostSignificantBits();
        FixedBiomeSource biomeSource = new FixedBiomeSource(biome);
        BoundedNoiseBasedChunkGenerator generator = new BoundedNoiseBasedChunkGenerator(
                structureSets,
                noiseParameters,
                biomeSource,
                biome,
                seed,
                noiseSettings.getHolderOrThrow(NoiseGeneratorSettings.OVERWORLD),
                MIN_POCKET_CHUNK,
                MAX_POCKET_CHUNK
        );
        LevelStem stem = new LevelStem(dimensionTypes.getHolderOrThrow(DimensionType.OVERWORLD_LOCATION), generator);
        ResourceKey<LevelStem> stemKey = ResourceKey.create(Registry.LEVEL_STEM_REGISTRY, levelKey.location());

        WorldData worldData = server.getWorldData();
        WorldGenSettings worldGenSettings = worldData.worldGenSettings();
        Registry<LevelStem> dimensionRegistry = worldGenSettings.dimensions();
        if (!(dimensionRegistry instanceof WritableRegistry<LevelStem> writableRegistry)) {
            throw new IllegalStateException("Server dimension registry is not writable");
        }
        writableRegistry.register(stemKey, stem, Lifecycle.stable());

        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) {
            throw new IllegalStateException("Overworld is unavailable");
        }

        ChunkProgressListener progressListener = server.progressListenerFactory.create(11);
        Executor executor = server.executor;
        LevelStorageSource.LevelStorageAccess storageSource = server.storageSource;
        DerivedLevelData derivedLevelData = new DerivedLevelData(worldData, worldData.overworldData());

        ServerLevel newLevel = new ServerLevel(
                server,
                executor,
                storageSource,
                derivedLevelData,
                levelKey,
                stem.typeHolder(),
                progressListener,
                stem.generator(),
                worldGenSettings.isDebug(),
                BiomeManager.obfuscateSeed(seed),
                ImmutableList.of(),
                false
        );

        worlds.put(levelKey, newLevel);
        server.markWorldsDirty();
        MinecraftForge.EVENT_BUS.post(new WorldEvent.Load(newLevel));
        return newLevel;
    }

    @SuppressWarnings("deprecation")
    private static void teardown(MinecraftServer server, ResourceKey<Level> key, PocketRecord record, boolean force) {
        if (!OWNED.containsKey(key) || !isPocketKey(key)) {
            return;
        }

        ServerLevel level = server.getLevel(key);
        if (level == null) {
            OWNED.remove(key);
            return;
        }

        if (force && !level.players().isEmpty()) {
            ServerLevel overworld = server.getLevel(Level.OVERWORLD);
            if (overworld == null) {
                return;
            }
            for (ServerPlayer player : new ArrayList<>(level.players())) {
                player.teleportTo(overworld,
                        overworld.getSharedSpawnPos().getX() + 0.5D,
                        overworld.getSharedSpawnPos().getY() + 1.0D,
                        overworld.getSharedSpawnPos().getZ() + 0.5D,
                        player.getYRot(), player.getXRot());
            }
        }

        if (!level.players().isEmpty()) {
            return;
        }

        Map<ResourceKey<Level>, ServerLevel> worlds = server.forgeGetWorldMap();
        ServerLevel removed = worlds.get(key);
        if (removed != level) {
            return;
        }
        worlds.remove(key);

        try {
            removed.save(null, false, removed.noSave());
        } catch (Exception exception) {
            BiomePockets.LOGGER.warn("Failed to save pocket {} before teardown", key.location(), exception);
        }

        MinecraftForge.EVENT_BUS.post(new WorldEvent.Unload(removed));

        try {
            removed.close();
        } catch (IOException exception) {
            BiomePockets.LOGGER.warn("Failed to close pocket {} cleanly", key.location(), exception);
        }

        removeLevelStem(server, key);
        server.markWorldsDirty();
        OWNED.remove(key);
        deleteOwnedFolder(server, key, record.folder());
    }

    private static void removeLevelStem(MinecraftServer server, ResourceKey<Level> levelKey) {
        WorldGenSettings worldGenSettings = server.getWorldData().worldGenSettings();
        Registry<LevelStem> oldRegistry = worldGenSettings.dimensions();
        Registry<LevelStem> newRegistry = new MappedRegistry<>(Registry.LEVEL_STEM_REGISTRY, oldRegistry.lifecycle(), null);

        for (Map.Entry<ResourceKey<LevelStem>, LevelStem> entry : oldRegistry.entrySet()) {
            ResourceKey<LevelStem> stemKey = entry.getKey();
            ResourceKey<Level> candidate = ResourceKey.create(Registry.DIMENSION_REGISTRY, stemKey.location());
            if (!candidate.equals(levelKey)) {
                Registry.register(newRegistry, stemKey, entry.getValue());
            }
        }
        worldGenSettings.dimensions = newRegistry;
    }

    private static boolean isPocketKey(ResourceKey<Level> key) {
        ResourceLocation id = key.location();
        return BiomePockets.MOD_ID.equals(id.getNamespace()) && id.getPath().startsWith(POCKET_PREFIX);
    }

    private static Path pocketFolder(MinecraftServer server, ResourceKey<Level> key) {
        return DimensionType.getStorageFolder(key, server.getWorldPath(LevelResource.ROOT)).toAbsolutePath().normalize();
    }

    private static void writeOwnershipMarker(ResourceKey<Level> key, Path folder) {
        try {
            Files.createDirectories(folder);
            Files.writeString(folder.resolve(MARKER_FILE), key.location().toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException exception) {
            BiomePockets.LOGGER.warn("Could not write ownership marker for {}; its folder will not be auto-deleted", key.location(), exception);
        }
    }

    private static boolean hasValidOwnershipMarker(ResourceKey<Level> key, Path folder) {
        if (!isPocketKey(key)) {
            return false;
        }
        Path marker = folder.resolve(MARKER_FILE);
        try {
            return Files.isRegularFile(marker)
                    && Files.readString(marker, StandardCharsets.UTF_8).trim().equals(key.location().toString());
        } catch (IOException ignored) {
            return false;
        }
    }

    private static void deleteOwnedFolder(MinecraftServer server, ResourceKey<Level> key, Path folder) {
        if (!isPocketKey(key) || !hasValidOwnershipMarker(key, folder)) {
            return;
        }

        Path expectedRoot = server.getWorldPath(LevelResource.ROOT)
                .resolve("dimensions")
                .resolve(BiomePockets.MOD_ID)
                .toAbsolutePath()
                .normalize();
        Path normalized = folder.toAbsolutePath().normalize();
        if (!normalized.startsWith(expectedRoot)
                || normalized.equals(expectedRoot)
                || normalized.getFileName() == null
                || !normalized.getFileName().toString().equals(key.location().getPath())) {
            BiomePockets.LOGGER.error("Refusing to delete unexpected pocket path {} for {}", normalized, key.location());
            return;
        }

        try (Stream<Path> paths = Files.walk(normalized)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    BiomePockets.LOGGER.warn("Could not delete pocket path {}", path, exception);
                }
            });
        } catch (IOException exception) {
            BiomePockets.LOGGER.warn("Could not walk pocket directory {} for deletion", normalized, exception);
        }
    }

    private record PocketRecord(ResourceLocation biome, Path folder) { }
}
