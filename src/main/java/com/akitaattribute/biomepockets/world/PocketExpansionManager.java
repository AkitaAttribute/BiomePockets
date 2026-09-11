package com.akitaattribute.biomepockets.world;

import com.akitaattribute.biomepockets.BiomePockets;
import com.google.common.collect.ImmutableList;
import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Lifecycle;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.WritableRegistry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
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
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.levelgen.feature.ConfiguredStructureFeature;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
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
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Expands a permanent pocket without replacing its dimension.
 *
 * A disposable staging level is generated with the claimed pocket's original biome
 * and seed. Only the newly unlocked playable ring and its new outer barrier ring are
 * copied into the existing claimed ServerLevel. Existing playable chunks are never
 * overwritten, so player builds remain untouched and the claimed dimension key stays
 * stable for beds, maps, Visit/Exit state, and other dimension-keyed data.
 */
public final class PocketExpansionManager {
    private static final String POCKET_PREFIX = "pocket_";
    private static final String STAGING_PREFIX = "pocket_staging_";
    private static final String MARKER_FILE = ".biomepockets-owned";
    private static final String PERSISTENCE_FILE = ".biomepockets-meta.properties";

    private static final TicketType<ResourceLocation> EXPANSION_TICKET = TicketType.create(
            "biomepockets_expand",
            Comparator.comparing(ResourceLocation::toString));
    private static final int TICKET_RADIUS = 1;

    // Protect active staging dimensions from the explicit admin cleanup command. After
    // a crash/restart this set is naturally empty, allowing startup stale cleanup to
    // remove an abandoned staging dimension through the normal ownership safeguards.
    private static final Set<ResourceKey<Level>> ACTIVE_STAGING = ConcurrentHashMap.newKeySet();

    private PocketExpansionManager() { }

    public static boolean isActiveStaging(ResourceKey<Level> dimension) {
        return ACTIVE_STAGING.contains(dimension);
    }

    public static void expand(
            ServerPlayer requester,
            ResourceKey<Level> claimedDimension,
            ResourceLocation biomeId,
            long seed,
            int oldRadius,
            int newRadius,
            int cost) {
        MinecraftServer server = requester.getServer();
        Optional<Holder<Biome>> biome = BiomeCatalog.getBiome(server, biomeId);
        if (biome.isEmpty()) {
            PocketClaimManager.expansionFailed(
                    server,
                    requester.getUUID(),
                    "Expansion failed: biome is no longer registered.");
            return;
        }

        ServerLevel claimedLevel = server.getLevel(claimedDimension);
        if (claimedLevel == null
                || !(claimedLevel.getChunkSource().getGenerator() instanceof BoundedNoiseBasedChunkGenerator)) {
            PocketClaimManager.expansionFailed(
                    server,
                    requester.getUUID(),
                    "Expansion failed: claimed pocket is not currently available.");
            return;
        }

        ResourceLocation stagingId = new ResourceLocation(
                BiomePockets.MOD_ID,
                STAGING_PREFIX + UUID.randomUUID().toString().replace("-", ""));
        ResourceKey<Level> stagingDimension = ResourceKey.create(Registry.DIMENSION_REGISTRY, stagingId);

        try {
            ServerLevel staging = createLevel(server, stagingDimension, biome.get(), seed, newRadius);
            Path folder = pocketFolder(server, stagingDimension);
            writeOwnershipMarker(stagingDimension, folder);
            adoptOwnedPocket(stagingDimension, biomeId, folder);
            writePersistenceMetadata(stagingDimension, biomeId, folder);
            PocketClaimManager.writeGeometry(server, stagingDimension, newRadius, seed);
            ACTIVE_STAGING.add(stagingDimension);

            prepareStagingAsync(
                    server,
                    staging,
                    stagingDimension,
                    claimedDimension,
                    requester.getUUID(),
                    oldRadius,
                    newRadius,
                    seed,
                    cost);
        } catch (Exception exception) {
            ACTIVE_STAGING.remove(stagingDimension);
            BiomePockets.LOGGER.error("Could not create expansion staging pocket {}", stagingId, exception);
            PocketClaimManager.expansionFailed(
                    server,
                    requester.getUUID(),
                    "Biome pocket expansion failed while creating staging terrain.");
            PocketDimensionManager.teardownIfEmpty(server, stagingDimension);
        }
    }

    private static void prepareStagingAsync(
            MinecraftServer server,
            ServerLevel staging,
            ResourceKey<Level> stagingDimension,
            ResourceKey<Level> claimedDimension,
            UUID playerId,
            int oldRadius,
            int newRadius,
            long seed,
            int cost) {
        List<ChunkPos> playableRing = ring(newRadius);
        List<ChunkPos> barrierRing = ring(newRadius + 1);
        List<ChunkPos> affected = concat(playableRing, barrierRing);
        ResourceLocation ticketOwner = stagingDimension.location();
        ServerChunkCache chunkSource = staging.getChunkSource();
        List<CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> futures =
                requestFullChunks(chunkSource, affected, ticketOwner);

        CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0]))
                .whenComplete((ignored, throwable) -> server.execute(() -> {
                    if (server.getLevel(stagingDimension) != staging) {
                        return;
                    }
                    if (throwable != null || !allSucceeded(futures)) {
                        failAndCleanup(
                                server,
                                staging,
                                stagingDimension,
                                null,
                                affected,
                                playerId,
                                ticketOwner,
                                "Biome pocket expansion staging generation failed.",
                                throwable);
                        return;
                    }

                    if (!(staging.getChunkSource().getGenerator() instanceof BoundedNoiseBasedChunkGenerator generator)) {
                        failAndCleanup(
                                server,
                                staging,
                                stagingDimension,
                                null,
                                affected,
                                playerId,
                                ticketOwner,
                                "Biome pocket expansion staging generator became unavailable.",
                                null);
                        return;
                    }

                    int repaired = 0;
                    for (ChunkPos pos : barrierRing) {
                        repaired += generator.repairBarrierChunk(staging.getChunk(pos.x, pos.z));
                    }
                    int removedVines = generator.removeBarrierSupportedVines(staging);
                    if (repaired > 0 || removedVines > 0) {
                        BiomePockets.LOGGER.info(
                                "Expansion staging {} repaired {} barrier blocks and removed {} wall vines",
                                stagingDimension.location(),
                                repaired,
                                removedVines);
                    }

                    if (!PocketClaimExpansionBridge.canCommit(server, playerId, claimedDimension, cost)) {
                        failAndCleanup(
                                server,
                                staging,
                                stagingDimension,
                                null,
                                affected,
                                playerId,
                                ticketOwner,
                                "Biome pocket expansion was cancelled before applying staging terrain.",
                                null);
                        return;
                    }

                    Map<ChunkPos, ChunkSnapshot> stagedSnapshots = snapshotChunks(staging, affected);
                    prepareTargetAsync(
                            server,
                            staging,
                            stagingDimension,
                            claimedDimension,
                            playerId,
                            oldRadius,
                            newRadius,
                            seed,
                            cost,
                            ticketOwner,
                            affected,
                            stagedSnapshots);
                }));
    }

    private static void prepareTargetAsync(
            MinecraftServer server,
            ServerLevel staging,
            ResourceKey<Level> stagingDimension,
            ResourceKey<Level> claimedDimension,
            UUID playerId,
            int oldRadius,
            int newRadius,
            long seed,
            int cost,
            ResourceLocation ticketOwner,
            List<ChunkPos> affected,
            Map<ChunkPos, ChunkSnapshot> stagedSnapshots) {
        ServerLevel target = server.getLevel(claimedDimension);
        if (target == null) {
            failAndCleanup(
                    server,
                    staging,
                    stagingDimension,
                    null,
                    affected,
                    playerId,
                    ticketOwner,
                    "Biome pocket expansion was cancelled because the claimed pocket is unavailable.",
                    null);
            return;
        }

        ServerChunkCache targetSource = target.getChunkSource();
        List<CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> targetFutures =
                requestFullChunks(targetSource, affected, ticketOwner);

        CompletableFuture.allOf(targetFutures.toArray(new CompletableFuture<?>[0]))
                .whenComplete((ignored, throwable) -> server.execute(() -> {
                    if (server.getLevel(stagingDimension) != staging
                            || server.getLevel(claimedDimension) != target) {
                        return;
                    }
                    if (throwable != null || !allSucceeded(targetFutures)) {
                        failAndCleanup(
                                server,
                                staging,
                                stagingDimension,
                                target,
                                affected,
                                playerId,
                                ticketOwner,
                                "Biome pocket expansion could not prepare the destination chunks.",
                                throwable);
                        return;
                    }
                    if (!PocketClaimExpansionBridge.canCommit(server, playerId, claimedDimension, cost)) {
                        failAndCleanup(
                                server,
                                staging,
                                stagingDimension,
                                target,
                                affected,
                                playerId,
                                ticketOwner,
                                "Biome pocket expansion was cancelled before modifying the claimed pocket.",
                                null);
                        return;
                    }
                    if (!(targetSource.getGenerator() instanceof BoundedNoiseBasedChunkGenerator targetGenerator)) {
                        failAndCleanup(
                                server,
                                staging,
                                stagingDimension,
                                target,
                                affected,
                                playerId,
                                ticketOwner,
                                "Biome pocket expansion destination generator is unavailable.",
                                null);
                        return;
                    }

                    Map<ChunkPos, ChunkSnapshot> rollbackSnapshots = snapshotChunks(target, affected);
                    List<LevelChunk> changedChunks = new ArrayList<>(affected.size());
                    try {
                        for (ChunkPos pos : affected) {
                            LevelChunk targetChunk = target.getChunk(pos.x, pos.z);
                            ChunkSnapshot source = stagedSnapshots.get(pos);
                            if (source == null) {
                                throw new IllegalStateException("Missing staging snapshot for " + pos);
                            }
                            applySnapshot(targetChunk, source);
                            changedChunks.add(targetChunk);
                        }
                        targetGenerator.resizePocket(newRadius);
                    } catch (Exception exception) {
                        restoreSnapshots(target, rollbackSnapshots);
                        targetGenerator.resizePocket(oldRadius);
                        failAndCleanup(
                                server,
                                staging,
                                stagingDimension,
                                target,
                                affected,
                                playerId,
                                ticketOwner,
                                "Biome pocket expansion failed while copying staging terrain.",
                                exception);
                        return;
                    }

                    relightAndCommit(
                            server,
                            staging,
                            stagingDimension,
                            target,
                            claimedDimension,
                            playerId,
                            oldRadius,
                            newRadius,
                            seed,
                            cost,
                            ticketOwner,
                            affected,
                            changedChunks,
                            rollbackSnapshots,
                            targetGenerator);
                }));
    }

    private static void relightAndCommit(
            MinecraftServer server,
            ServerLevel staging,
            ResourceKey<Level> stagingDimension,
            ServerLevel target,
            ResourceKey<Level> claimedDimension,
            UUID playerId,
            int oldRadius,
            int newRadius,
            long seed,
            int cost,
            ResourceLocation ticketOwner,
            List<ChunkPos> affected,
            List<LevelChunk> changedChunks,
            Map<ChunkPos, ChunkSnapshot> rollbackSnapshots,
            BoundedNoiseBasedChunkGenerator targetGenerator) {
        List<CompletableFuture<ChunkAccess>> lightFutures = new ArrayList<>(changedChunks.size());
        for (LevelChunk chunk : changedChunks) {
            chunk.setLightCorrect(false);
            lightFutures.add(target.getChunkSource().getLightEngine().lightChunk(chunk, false));
        }

        CompletableFuture.allOf(lightFutures.toArray(new CompletableFuture<?>[0]))
                .whenComplete((ignored, throwable) -> server.execute(() -> {
                    if (throwable != null) {
                        restoreSnapshots(target, rollbackSnapshots);
                        targetGenerator.resizePocket(oldRadius);
                        relightBestEffort(target, affected);
                        failAndCleanup(
                                server,
                                staging,
                                stagingDimension,
                                target,
                                affected,
                                playerId,
                                ticketOwner,
                                "Biome pocket expansion failed while lighting copied terrain.",
                                throwable);
                        return;
                    }

                    if (!PocketClaimExpansionBridge.canCommit(server, playerId, claimedDimension, cost)) {
                        restoreSnapshots(target, rollbackSnapshots);
                        targetGenerator.resizePocket(oldRadius);
                        relightBestEffort(target, affected);
                        failAndCleanup(
                                server,
                                staging,
                                stagingDimension,
                                target,
                                affected,
                                playerId,
                                ticketOwner,
                                "Biome pocket expansion was cancelled before saving the expanded claim.",
                                null);
                        return;
                    }

                    if (!PocketClaimExpansionBridge.commit(
                            server,
                            playerId,
                            claimedDimension,
                            newRadius,
                            seed,
                            cost)) {
                        restoreSnapshots(target, rollbackSnapshots);
                        targetGenerator.resizePocket(oldRadius);
                        relightBestEffort(target, affected);
                        failAndCleanup(
                                server,
                                staging,
                                stagingDimension,
                                target,
                                affected,
                                playerId,
                                ticketOwner,
                                "Biome pocket expansion could not save the expanded claim.",
                                null);
                        return;
                    }

                    refreshClientChunks(target, changedChunks);
                    removeTickets(staging, affected, ticketOwner);
                    removeTickets(target, affected, ticketOwner);
                    ACTIVE_STAGING.remove(stagingDimension);
                    PocketDimensionManager.teardownIfEmpty(server, stagingDimension);

                    BiomePockets.LOGGER.info(
                            "Expanded claimed pocket {} in place from {}x{} to {}x{} using staging {}",
                            claimedDimension.location(),
                            oldRadius * 2 + 1,
                            oldRadius * 2 + 1,
                            newRadius * 2 + 1,
                            newRadius * 2 + 1,
                            stagingDimension.location());
                }));
    }

    private static void refreshClientChunks(ServerLevel level, List<LevelChunk> chunks) {
        if (level.players().isEmpty()) {
            return;
        }
        for (LevelChunk chunk : chunks) {
            ClientboundLevelChunkWithLightPacket packet = new ClientboundLevelChunkWithLightPacket(
                    chunk,
                    level.getChunkSource().getLightEngine(),
                    null,
                    null,
                    true);
            for (ServerPlayer player : level.players()) {
                player.connection.send(packet);
            }
        }
    }

    private static Map<ChunkPos, ChunkSnapshot> snapshotChunks(ServerLevel level, List<ChunkPos> positions) {
        Map<ChunkPos, ChunkSnapshot> snapshots = new LinkedHashMap<>();
        for (ChunkPos pos : positions) {
            LevelChunk chunk = level.getChunk(pos.x, pos.z);
            snapshots.put(pos, snapshot(chunk));
        }
        return snapshots;
    }

    private static ChunkSnapshot snapshot(LevelChunk chunk) {
        LevelChunkSection[] sourceSections = chunk.getSections();
        LevelChunkSection[] sections = new LevelChunkSection[sourceSections.length];
        for (int i = 0; i < sourceSections.length; i++) {
            LevelChunkSection section = sourceSections[i];
            sections[i] = new LevelChunkSection(
                    section.bottomBlockY(),
                    section.getStates().copy(),
                    section.getBiomes().copy());
        }

        Map<BlockPos, CompoundTag> blockEntities = new HashMap<>();
        for (BlockPos pos : chunk.getBlockEntitiesPos()) {
            CompoundTag tag = chunk.getBlockEntityNbtForSaving(pos);
            if (tag != null) {
                blockEntities.put(pos.immutable(), tag.copy());
            }
        }

        Map<Heightmap.Types, long[]> heightmaps = new EnumMap<>(Heightmap.Types.class);
        for (Map.Entry<Heightmap.Types, Heightmap> entry : chunk.getHeightmaps()) {
            heightmaps.put(entry.getKey(), entry.getValue().getRawData().clone());
        }

        Map<ConfiguredStructureFeature<?, ?>, StructureStart> starts =
                new HashMap<>(chunk.getAllStarts());
        Map<ConfiguredStructureFeature<?, ?>, LongSet> references = new HashMap<>();
        for (Map.Entry<ConfiguredStructureFeature<?, ?>, LongSet> entry : chunk.getAllReferences().entrySet()) {
            references.put(entry.getKey(), new LongOpenHashSet(entry.getValue()));
        }

        return new ChunkSnapshot(
                sections,
                blockEntities,
                heightmaps,
                starts,
                references,
                chunk.getInhabitedTime());
    }

    private static void applySnapshot(LevelChunk target, ChunkSnapshot snapshot) {
        LevelChunkSection[] destinationSections = target.getSections();
        if (destinationSections.length != snapshot.sections().length) {
            throw new IllegalStateException("Chunk section count differs between staging and claimed pocket");
        }

        target.clearAllBlockEntities();
        for (int i = 0; i < destinationSections.length; i++) {
            LevelChunkSection section = snapshot.sections()[i];
            destinationSections[i] = new LevelChunkSection(
                    section.bottomBlockY(),
                    section.getStates().copy(),
                    section.getBiomes().copy());
        }

        for (Map.Entry<Heightmap.Types, long[]> entry : snapshot.heightmaps().entrySet()) {
            target.setHeightmap(entry.getKey(), entry.getValue().clone());
        }
        target.setAllStarts(new HashMap<>(snapshot.starts()));

        Map<ConfiguredStructureFeature<?, ?>, LongSet> references = new HashMap<>();
        for (Map.Entry<ConfiguredStructureFeature<?, ?>, LongSet> entry : snapshot.references().entrySet()) {
            references.put(entry.getKey(), new LongOpenHashSet(entry.getValue()));
        }
        target.setAllReferences(references);

        for (Map.Entry<BlockPos, CompoundTag> entry : snapshot.blockEntities().entrySet()) {
            target.setBlockEntityNbt(entry.getValue().copy());
            target.getBlockEntity(entry.getKey(), LevelChunk.EntityCreationType.IMMEDIATE);
        }

        target.setInhabitedTime(snapshot.inhabitedTime());
        target.setLightCorrect(false);
        target.setUnsaved(true);
    }

    private static void restoreSnapshots(ServerLevel target, Map<ChunkPos, ChunkSnapshot> snapshots) {
        for (Map.Entry<ChunkPos, ChunkSnapshot> entry : snapshots.entrySet()) {
            ChunkPos pos = entry.getKey();
            applySnapshot(target.getChunk(pos.x, pos.z), entry.getValue());
        }
    }

    private static void relightBestEffort(ServerLevel level, List<ChunkPos> positions) {
        for (ChunkPos pos : positions) {
            try {
                LevelChunk chunk = level.getChunk(pos.x, pos.z);
                chunk.setLightCorrect(false);
                level.getChunkSource().getLightEngine().lightChunk(chunk, false);
            } catch (Exception exception) {
                BiomePockets.LOGGER.warn("Could not relight rolled-back chunk {}", pos, exception);
            }
        }
    }

    private static List<CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> requestFullChunks(
            ServerChunkCache chunkSource,
            List<ChunkPos> positions,
            ResourceLocation ticketOwner) {
        List<CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> futures =
                new ArrayList<>(positions.size());
        for (ChunkPos pos : positions) {
            chunkSource.addRegionTicket(EXPANSION_TICKET, pos, TICKET_RADIUS, ticketOwner);
            futures.add(chunkSource.getChunkFuture(pos.x, pos.z, ChunkStatus.FULL, true));
        }
        return futures;
    }

    private static List<ChunkPos> ring(int radius) {
        List<ChunkPos> positions = new ArrayList<>(Math.max(1, radius * 8));
        for (int x = -radius; x <= radius; x++) {
            positions.add(new ChunkPos(x, -radius));
            if (radius != 0) {
                positions.add(new ChunkPos(x, radius));
            }
        }
        for (int z = -radius + 1; z <= radius - 1; z++) {
            positions.add(new ChunkPos(-radius, z));
            if (radius != 0) {
                positions.add(new ChunkPos(radius, z));
            }
        }
        return positions;
    }

    private static List<ChunkPos> concat(List<ChunkPos> first, List<ChunkPos> second) {
        List<ChunkPos> result = new ArrayList<>(first.size() + second.size());
        result.addAll(first);
        result.addAll(second);
        return result;
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

    private static void removeTickets(
            ServerLevel level,
            List<ChunkPos> positions,
            ResourceLocation ticketOwner) {
        ServerChunkCache chunkSource = level.getChunkSource();
        for (ChunkPos pos : positions) {
            chunkSource.removeRegionTicket(EXPANSION_TICKET, pos, TICKET_RADIUS, ticketOwner);
        }
    }

    private static void failAndCleanup(
            MinecraftServer server,
            ServerLevel staging,
            ResourceKey<Level> stagingDimension,
            ServerLevel target,
            List<ChunkPos> affected,
            UUID playerId,
            ResourceLocation ticketOwner,
            String message,
            Throwable throwable) {
        if (throwable != null) {
            BiomePockets.LOGGER.error(message, throwable);
        } else {
            BiomePockets.LOGGER.warn(message);
        }
        removeTickets(staging, affected, ticketOwner);
        if (target != null) {
            removeTickets(target, affected, ticketOwner);
        }
        ACTIVE_STAGING.remove(stagingDimension);
        PocketClaimManager.expansionFailed(server, playerId, message);
        PocketDimensionManager.teardownIfEmpty(server, stagingDimension);
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
            throw new IllegalStateException("Expansion staging dimension already exists: " + levelKey.location());
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
                noiseSettings.getHolderOrThrow(profile.noiseSettings()),
                -radius,
                radius);
        LevelStem stem = new LevelStem(dimensionTypes.getHolderOrThrow(profile.dimensionType()), generator);
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
        PocketServerLevelData derivedLevelData = new PocketServerLevelData(worldData, worldData.overworldData());

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
            properties.store(output, "BiomePockets expansion staging dimension");
        }
    }

    private static Path pocketFolder(MinecraftServer server, ResourceKey<Level> key) {
        return DimensionType.getStorageFolder(key, server.getWorldPath(LevelResource.ROOT))
                .toAbsolutePath()
                .normalize();
    }

    private record ChunkSnapshot(
            LevelChunkSection[] sections,
            Map<BlockPos, CompoundTag> blockEntities,
            Map<Heightmap.Types, long[]> heightmaps,
            Map<ConfiguredStructureFeature<?, ?>, StructureStart> starts,
            Map<ConfiguredStructureFeature<?, ?>, LongSet> references,
            long inhabitedTime) { }

    private record GenerationProfile(
            ResourceKey<NoiseGeneratorSettings> noiseSettings,
            ResourceKey<DimensionType> dimensionType) { }
}
