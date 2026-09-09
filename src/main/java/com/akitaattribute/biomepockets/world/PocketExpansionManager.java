package com.akitaattribute.biomepockets.world;

import com.akitaattribute.biomepockets.BiomePockets;
import com.google.common.collect.ImmutableList;
import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Lifecycle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.WritableRegistry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkProgressListener;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.WorldData;
import net.minecraftforge.common.BiomeDictionary;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.Tags;
import net.minecraftforge.event.world.WorldEvent;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Generates a larger same-biome/same-seed replacement pocket and migrates the old
 * playable square into it. This avoids trying to turn already-FULL barrier chunks
 * back into normal worldgen chunks in-place, which Minecraft's chunk pipeline is not
 * designed to do.
 */
public final class PocketExpansionManager {
    private static final String POCKET_PREFIX = "pocket_";
    private static final String MARKER_FILE = ".biomepockets-owned";
    private static final String PERSISTENCE_FILE = ".biomepockets-meta.properties";

    private static final TicketType<ResourceLocation> EXPANSION_TICKET = TicketType.create(
            "biomepockets_expand",
            Comparator.comparing(ResourceLocation::toString));
    private static final int TICKET_RADIUS = 1;

    private PocketExpansionManager() { }

    public static void expand(
            ServerPlayer requester,
            ResourceKey<Level> oldDimension,
            ResourceLocation biomeId,
            long seed,
            int oldRadius,
            int newRadius,
            int cost) {
        MinecraftServer server = requester.getServer();
        Optional<Holder<Biome>> biome = BiomeCatalog.getBiome(server, biomeId);
        if (biome.isEmpty()) {
            PocketClaimManager.expansionFailed(server, requester.getUUID(), "Expansion failed: biome is no longer registered.");
            return;
        }

        ResourceLocation dimensionId = new ResourceLocation(
                BiomePockets.MOD_ID,
                POCKET_PREFIX + UUID.randomUUID().toString().replace("-", ""));
        ResourceKey<Level> newDimension = ResourceKey.create(Registry.DIMENSION_REGISTRY, dimensionId);

        try {
            ServerLevel expanded = createLevel(server, newDimension, biome.get(), seed, newRadius);
            Path folder = pocketFolder(server, newDimension);
            writeOwnershipMarker(newDimension, folder);
            adoptOwnedPocket(newDimension, biomeId, folder);
            writePersistenceMetadata(newDimension, biomeId, folder);
            PocketClaimManager.writeGeometry(server, newDimension, newRadius, seed);

            prepareExpandedPocketAsync(
                    server,
                    expanded,
                    oldDimension,
                    newDimension,
                    requester.getUUID(),
                    oldRadius,
                    newRadius,
                    cost);
        } catch (Exception exception) {
            BiomePockets.LOGGER.error("Could not create expanded pocket {}", newDimension.location(), exception);
            PocketClaimManager.expansionFailed(server, requester.getUUID(), "Biome pocket expansion failed. See server log.");
            PocketDimensionManager.teardownIfEmpty(server, newDimension);
        }
    }

    private static void prepareExpandedPocketAsync(
            MinecraftServer server,
            ServerLevel expanded,
            ResourceKey<Level> oldDimension,
            ResourceKey<Level> newDimension,
            UUID playerId,
            int oldRadius,
            int newRadius,
            int cost) {
        ServerChunkCache chunkSource = expanded.getChunkSource();
        ResourceLocation ticketOwner = newDimension.location();
        int width = newRadius * 2 + 1;
        List<CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> playableFutures =
                new ArrayList<>(width * width);

        for (int chunkX = -newRadius; chunkX <= newRadius; chunkX++) {
            for (int chunkZ = -newRadius; chunkZ <= newRadius; chunkZ++) {
                ChunkPos pos = new ChunkPos(chunkX, chunkZ);
                chunkSource.addRegionTicket(EXPANSION_TICKET, pos, TICKET_RADIUS, ticketOwner);
                playableFutures.add(chunkSource.getChunkFuture(chunkX, chunkZ, ChunkStatus.FULL, true));
            }
        }

        CompletableFuture<Void> allPlayable = CompletableFuture.allOf(
                playableFutures.toArray(new CompletableFuture<?>[0]));
        allPlayable.whenComplete((ignored, throwable) -> server.execute(() -> {
            if (server.getLevel(newDimension) != expanded) {
                return;
            }
            if (throwable != null || !allSucceeded(playableFutures)) {
                failAndCleanup(
                        server,
                        expanded,
                        newDimension,
                        playerId,
                        newRadius,
                        ticketOwner,
                        "Biome pocket expansion terrain generation failed.",
                        throwable);
                return;
            }

            prepareBarrierRingAsync(
                    server,
                    expanded,
                    oldDimension,
                    newDimension,
                    playerId,
                    oldRadius,
                    newRadius,
                    cost,
                    ticketOwner);
        }));
    }

    private static void prepareBarrierRingAsync(
            MinecraftServer server,
            ServerLevel expanded,
            ResourceKey<Level> oldDimension,
            ResourceKey<Level> newDimension,
            UUID playerId,
            int oldRadius,
            int newRadius,
            int cost,
            ResourceLocation ticketOwner) {
        ServerChunkCache chunkSource = expanded.getChunkSource();
        if (!(chunkSource.getGenerator() instanceof BoundedNoiseBasedChunkGenerator generator)) {
            failAndCleanup(
                    server,
                    expanded,
                    newDimension,
                    playerId,
                    newRadius,
                    ticketOwner,
                    "Biome pocket expansion lost its bounded generator.",
                    null);
            return;
        }

        int barrierRadius = newRadius + 1;
        List<CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> barrierFutures =
                new ArrayList<>(barrierRadius * 8);
        for (int chunkX = -barrierRadius; chunkX <= barrierRadius; chunkX++) {
            for (int chunkZ = -barrierRadius; chunkZ <= barrierRadius; chunkZ++) {
                if (Math.abs(chunkX) <= newRadius && Math.abs(chunkZ) <= newRadius) {
                    continue;
                }
                barrierFutures.add(chunkSource.getChunkFuture(chunkX, chunkZ, ChunkStatus.FEATURES, true));
            }
        }

        CompletableFuture<Void> allBarrier = CompletableFuture.allOf(
                barrierFutures.toArray(new CompletableFuture<?>[0]));
        allBarrier.whenComplete((ignored, throwable) -> server.execute(() -> {
            if (server.getLevel(newDimension) != expanded) {
                return;
            }
            if (throwable != null || !allSucceeded(barrierFutures)) {
                failAndCleanup(
                        server,
                        expanded,
                        newDimension,
                        playerId,
                        newRadius,
                        ticketOwner,
                        "Biome pocket expansion containment generation failed.",
                        throwable);
                return;
            }

            int repaired = 0;
            for (CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> future : barrierFutures) {
                Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure> result = future.getNow(null);
                if (result != null && result.left().isPresent()) {
                    repaired += generator.repairBarrierChunk(result.left().get());
                }
            }
            int removedVines = generator.removeBarrierSupportedVines(expanded);
            if (repaired > 0 || removedVines > 0) {
                BiomePockets.LOGGER.info(
                        "Expanded pocket {} containment repaired {} blocks and removed {} wall vines",
                        newDimension.location(),
                        repaired,
                        removedVines);
            }

            ServerLevel oldLevel = server.getLevel(oldDimension);
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (oldLevel == null || player == null) {
                failAndCleanup(
                        server,
                        expanded,
                        newDimension,
                        playerId,
                        newRadius,
                        ticketOwner,
                        "Biome pocket expansion was cancelled because the original pocket or player is unavailable.",
                        null);
                return;
            }

            try {
                migratePlayableArea(oldLevel, expanded, oldRadius);
                removeTickets(expanded, newRadius, ticketOwner);
                PocketClaimManager.completeExpansion(
                        server,
                        playerId,
                        oldDimension,
                        newDimension,
                        newRadius,
                        cost);
            } catch (Exception exception) {
                BiomePockets.LOGGER.error(
                        "Could not migrate claimed pocket {} into expanded pocket {}",
                        oldDimension.location(),
                        newDimension.location(),
                        exception);
                failAndCleanup(
                        server,
                        expanded,
                        newDimension,
                        playerId,
                        newRadius,
                        ticketOwner,
                        "Biome pocket expansion failed while preserving existing contents.",
                        exception);
            }
        }));
    }

    /**
     * Replays differences from the old claimed square into the newly generated pocket.
     * The outer ring remains freshly generated and correctly lit. Existing blocks are
     * compared before writing, so normal terrain that matches the same-seed replacement
     * incurs no live-world block update.
     */
    private static void migratePlayableArea(ServerLevel oldLevel, ServerLevel newLevel, int oldRadius) {
        int minY = Math.max(oldLevel.getMinBuildHeight(), newLevel.getMinBuildHeight());
        int maxY = Math.min(oldLevel.getMaxBuildHeight(), newLevel.getMaxBuildHeight());
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int changedBlocks = 0;
        int copiedBlockEntities = 0;

        for (int chunkX = -oldRadius; chunkX <= oldRadius; chunkX++) {
            for (int chunkZ = -oldRadius; chunkZ <= oldRadius; chunkZ++) {
                LevelChunk oldChunk = oldLevel.getChunk(chunkX, chunkZ);
                LevelChunk newChunk = newLevel.getChunk(chunkX, chunkZ);
                int minX = chunkX << 4;
                int minZ = chunkZ << 4;

                for (int y = minY; y < maxY; y++) {
                    for (int localZ = 0; localZ < 16; localZ++) {
                        for (int localX = 0; localX < 16; localX++) {
                            pos.set(minX + localX, y, minZ + localZ);
                            BlockState oldState = oldChunk.getBlockState(pos);
                            BlockState newState = newChunk.getBlockState(pos);
                            if (!oldState.equals(newState)) {
                                newLevel.setBlock(pos, oldState, 2);
                                changedBlocks++;
                            }
                        }
                    }
                }

                for (BlockPos blockEntityPos : oldChunk.getBlockEntitiesPos()) {
                    CompoundTag tag = oldChunk.getBlockEntityNbtForSaving(blockEntityPos);
                    if (tag == null) {
                        continue;
                    }
                    BlockEntity target = newLevel.getBlockEntity(blockEntityPos);
                    if (target != null) {
                        target.load(tag.copy());
                        target.setChanged();
                        copiedBlockEntities++;
                    } else {
                        newChunk.setBlockEntityNbt(tag.copy());
                        copiedBlockEntities++;
                    }
                }
                newChunk.setUnsaved(true);
            }
        }

        BiomePockets.LOGGER.info(
                "Migrated claimed pocket contents: {} differing blocks and {} block entities",
                changedBlocks,
                copiedBlockEntities);
    }

    private static boolean allSucceeded(
            List<CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> futures) {
        for (CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> future : futures) {
            Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure> result = future.getNow(null);
            if (result == null || result.left().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static void removeTickets(ServerLevel level, int radius, ResourceLocation ticketOwner) {
        ServerChunkCache chunkSource = level.getChunkSource();
        for (int chunkX = -radius; chunkX <= radius; chunkX++) {
            for (int chunkZ = -radius; chunkZ <= radius; chunkZ++) {
                chunkSource.removeRegionTicket(
                        EXPANSION_TICKET,
                        new ChunkPos(chunkX, chunkZ),
                        TICKET_RADIUS,
                        ticketOwner);
            }
        }
    }

    private static void failAndCleanup(
            MinecraftServer server,
            ServerLevel level,
            ResourceKey<Level> dimension,
            UUID playerId,
            int radius,
            ResourceLocation ticketOwner,
            String message,
            Throwable throwable) {
        if (throwable != null) {
            BiomePockets.LOGGER.error(message, throwable);
        }
        removeTickets(level, radius, ticketOwner);
        PocketClaimManager.expansionFailed(server, playerId, message);
        PocketDimensionManager.teardownIfEmpty(server, dimension);
    }

    @SuppressWarnings({"deprecation", "removal"})
    private static ServerLevel createLevel(
            MinecraftServer server,
            ResourceKey<Level> levelKey,
            Holder<Biome> biome,
            long seed,
            int radius) {
        Map<ResourceKey<Level>, ServerLevel> worlds = server.forgeGetWorldMap();
        if (worlds.containsKey(levelKey)) {
            throw new IllegalStateException("Expanded pocket dimension already exists: " + levelKey.location());
        }

        Registry<StructureSet> structureSets = server.registryAccess().registryOrThrow(Registry.STRUCTURE_SET_REGISTRY);
        Registry<NormalNoise.NoiseParameters> noiseParameters = server.registryAccess().registryOrThrow(Registry.NOISE_REGISTRY);
        Registry<NoiseGeneratorSettings> noiseSettings = server.registryAccess().registryOrThrow(Registry.NOISE_GENERATOR_SETTINGS_REGISTRY);
        Registry<DimensionType> dimensionTypes = server.registryAccess().registryOrThrow(Registry.DIMENSION_TYPE_REGISTRY);

        GenerationProfile profile = generationProfile(biome);
        FixedBiomeSource biomeSource = new FixedBiomeSource(biome);
        BoundedNoiseBasedChunkGenerator generator = new BoundedNoiseBasedChunkGenerator(
                structureSets,
                noiseParameters,
                biomeSource,
                biome,
                seed,
                noiseSettings.getHolderOrThrow(profile.noiseSettings),
                -radius,
                radius);
        LevelStem stem = new LevelStem(dimensionTypes.getHolderOrThrow(profile.dimensionType), generator);
        ResourceKey<LevelStem> stemKey = ResourceKey.create(Registry.LEVEL_STEM_REGISTRY, levelKey.location());

        WorldData worldData = server.getWorldData();
        WorldGenSettings worldGenSettings = worldData.worldGenSettings();
        Registry<LevelStem> dimensionRegistry = worldGenSettings.dimensions();
        if (!(dimensionRegistry instanceof WritableRegistry<LevelStem> writableRegistry)) {
            throw new IllegalStateException("Server dimension registry is not writable");
        }
        writableRegistry.register(stemKey, stem, Lifecycle.stable());

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
                false);

        worlds.put(levelKey, newLevel);
        server.markWorldsDirty();
        MinecraftForge.EVENT_BUS.post(new WorldEvent.Load(newLevel));
        return newLevel;
    }

    @SuppressWarnings({"deprecation", "removal"})
    private static GenerationProfile generationProfile(Holder<Biome> biome) {
        boolean isNether = biome.is(BiomeTags.IS_NETHER);
        boolean isEnd = biome.is(Tags.Biomes.IS_END);
        Optional<ResourceKey<Biome>> biomeKey = biome.unwrapKey();
        if (biomeKey.isPresent()) {
            isNether = isNether || BiomeDictionary.hasType(biomeKey.get(), BiomeDictionary.Type.NETHER);
            isEnd = isEnd || BiomeDictionary.hasType(biomeKey.get(), BiomeDictionary.Type.END);
        }
        if (isEnd) {
            return new GenerationProfile(NoiseGeneratorSettings.END, DimensionType.END_LOCATION);
        }
        if (isNether) {
            return new GenerationProfile(NoiseGeneratorSettings.NETHER, DimensionType.NETHER_LOCATION);
        }
        return new GenerationProfile(NoiseGeneratorSettings.OVERWORLD, DimensionType.OVERWORLD_LOCATION);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void adoptOwnedPocket(
            ResourceKey<Level> key,
            ResourceLocation biome,
            Path folder) throws ReflectiveOperationException {
        Field ownedField = PocketDimensionManager.class.getDeclaredField("OWNED");
        ownedField.setAccessible(true);
        Map owned = (Map) ownedField.get(null);
        Class<?> recordClass = Class.forName(PocketDimensionManager.class.getName() + "$PocketRecord");
        Constructor<?> constructor = recordClass.getDeclaredConstructor(ResourceLocation.class, Path.class);
        constructor.setAccessible(true);
        owned.put(key, constructor.newInstance(biome, folder));
    }

    private static void writeOwnershipMarker(ResourceKey<Level> key, Path folder) throws IOException {
        Files.createDirectories(folder);
        Files.writeString(
                folder.resolve(MARKER_FILE),
                key.location().toString(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
    }

    private static void writePersistenceMetadata(
            ResourceKey<Level> key,
            ResourceLocation biome,
            Path folder) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("version", "1");
        properties.setProperty("dimension", key.location().toString());
        properties.setProperty("biome", biome.toString());
        try (OutputStream output = Files.newOutputStream(
                folder.resolve(PERSISTENCE_FILE),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            properties.store(output, "BiomePockets persistent runtime dimension");
        }
    }

    private static Path pocketFolder(MinecraftServer server, ResourceKey<Level> key) {
        return DimensionType.getStorageFolder(key, server.getWorldPath(LevelResource.ROOT))
                .toAbsolutePath()
                .normalize();
    }

    private record GenerationProfile(
            ResourceKey<NoiseGeneratorSettings> noiseSettings,
            ResourceKey<DimensionType> dimensionType) { }
}
