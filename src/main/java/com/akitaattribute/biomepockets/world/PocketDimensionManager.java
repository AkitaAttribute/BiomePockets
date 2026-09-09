package com.akitaattribute.biomepockets.world;

import com.akitaattribute.biomepockets.BiomePockets;
import com.google.common.collect.ImmutableList;
import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Lifecycle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
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
import net.minecraftforge.common.BiomeDictionary;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.Tags;
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
    private static final int MIN_BARRIER_CHUNK = MIN_POCKET_CHUNK - 1;
    private static final int MAX_BARRIER_CHUNK = MAX_POCKET_CHUNK + 1;
    private static final int MIN_POCKET_BLOCK = MIN_POCKET_CHUNK * 16;
    private static final int MAX_POCKET_BLOCK = ((MAX_POCKET_CHUNK + 1) * 16) - 1;

    // Keep the nine requested chunks resident while their asynchronous FULL futures
    // progress through terrain, FEATURES, lighting, and final chunk conversion.
    private static final TicketType<ResourceLocation> PREPARATION_TICKET = TicketType.create(
            "biomepockets_prepare",
            Comparator.comparing(ResourceLocation::toString));
    private static final int PREPARATION_TICKET_RADIUS = 1;

    private static final Map<ResourceKey<Level>, PocketRecord> OWNED = new HashMap<>();
    private static final Map<UUID, PocketReturnPoint> DISCONNECTED_PLAYERS = new HashMap<>();

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

            if (throwable != null || !allChunkFuturesSucceeded(futures)) {
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

            // Features are allowed to write one chunk beyond their source chunk. Some
            // features (for example lakes and End islands) can therefore overwrite the
            // barrier blocks created during NOISE. Do not teleport until the full ring
            // has reached FEATURES and has been repaired after all nine playable FULL
            // futures have completed.
            finalizeContainmentAsync(server, pocket, levelKey, record, playerId, ticketOwner);
        }));
    }

    private static void finalizeContainmentAsync(
            MinecraftServer server,
            ServerLevel pocket,
            ResourceKey<Level> levelKey,
            PocketRecord record,
            UUID playerId,
            ResourceLocation ticketOwner) {
        ServerChunkCache chunkSource = pocket.getChunkSource();
        if (!(chunkSource.getGenerator() instanceof BoundedNoiseBasedChunkGenerator generator)) {
            BiomePockets.LOGGER.error("Pocket {} is no longer using the bounded generator", levelKey.location());
            removePreparationTickets(pocket, ticketOwner);
            teardown(server, levelKey, record, false);
            return;
        }

        List<CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> barrierFutures =
                new ArrayList<>(16);
        for (int chunkX = MIN_BARRIER_CHUNK; chunkX <= MAX_BARRIER_CHUNK; chunkX++) {
            for (int chunkZ = MIN_BARRIER_CHUNK; chunkZ <= MAX_BARRIER_CHUNK; chunkZ++) {
                if (chunkX >= MIN_POCKET_CHUNK && chunkX <= MAX_POCKET_CHUNK
                        && chunkZ >= MIN_POCKET_CHUNK && chunkZ <= MAX_POCKET_CHUNK) {
                    continue;
                }
                barrierFutures.add(chunkSource.getChunkFuture(chunkX, chunkZ, ChunkStatus.FEATURES, true));
            }
        }

        CompletableFuture<Void> allBarrierChunks = CompletableFuture.allOf(
                barrierFutures.toArray(new CompletableFuture<?>[0]));

        allBarrierChunks.whenComplete((ignored, throwable) -> server.execute(() -> {
            if (server.getLevel(levelKey) != pocket || OWNED.get(levelKey) != record) {
                return;
            }

            if (throwable != null || !allChunkFuturesSucceeded(barrierFutures)) {
                BiomePockets.LOGGER.error(
                        "Pocket {} failed while preparing its containment ring",
                        levelKey.location(),
                        throwable);
                removePreparationTickets(pocket, ticketOwner);
                ServerPlayer currentPlayer = server.getPlayerList().getPlayer(playerId);
                if (currentPlayer != null) {
                    currentPlayer.displayClientMessage(
                            new TextComponent("Biome pocket containment failed. See server log."),
                            false);
                }
                teardown(server, levelKey, record, false);
                return;
            }

            int repairedBlocks = 0;
            for (CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> future : barrierFutures) {
                Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure> result = future.getNow(null);
                if (result != null && result.left().isPresent()) {
                    repairedBlocks += generator.repairBarrierChunk(result.left().get());
                }
            }
            int removedVines = generator.removeBarrierSupportedVines(pocket);

            if (repairedBlocks > 0 || removedVines > 0) {
                BiomePockets.LOGGER.info(
                        "Finalized pocket {} containment: restored {} barrier blocks and removed {} wall-supported vines",
                        levelKey.location(),
                        repairedBlocks,
                        removedVines);
            }

            ServerPlayer currentPlayer = server.getPlayerList().getPlayer(playerId);
            if (currentPlayer == null) {
                removePreparationTickets(pocket, ticketOwner);
                teardown(server, levelKey, record, false);
                return;
            }

            // Containment is now authoritative. Only after this point may the player
            // enter the pocket.
            BlockPos spawn = findSafeSpawn(pocket);
            currentPlayer.teleportTo(
                    pocket,
                    spawn.getX() + 0.5D,
                    spawn.getY(),
                    spawn.getZ() + 0.5D,
                    currentPlayer.getYRot(),
                    currentPlayer.getXRot());

            removePreparationTickets(pocket, ticketOwner);
        }));
    }

    private static boolean allChunkFuturesSucceeded(
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

    /**
     * Finds a standable block with two collision-free, fluid-free blocks for the
     * player's body. The search begins at the center and expands through the complete
     * 48x48 playable area. Nether pockets therefore find a cavern instead of using the
     * top bedrock heightmap, while End/void pockets can fall back to a small platform.
     */
    private static BlockPos findSafeSpawn(ServerLevel level) {
        int maxRadius = Math.max(Math.abs(MIN_POCKET_BLOCK), Math.abs(MAX_POCKET_BLOCK));
        for (int radius = 0; radius <= maxRadius; radius++) {
            for (int x = -radius; x <= radius; x++) {
                BlockPos candidate = findSafeInColumn(level, x, -radius);
                if (candidate != null) {
                    return candidate;
                }
                if (radius != 0) {
                    candidate = findSafeInColumn(level, x, radius);
                    if (candidate != null) {
                        return candidate;
                    }
                }
            }
            for (int z = -radius + 1; z <= radius - 1; z++) {
                BlockPos candidate = findSafeInColumn(level, -radius, z);
                if (candidate != null) {
                    return candidate;
                }
                if (radius != 0) {
                    candidate = findSafeInColumn(level, radius, z);
                    if (candidate != null) {
                        return candidate;
                    }
                }
            }
        }
        return buildEmergencySpawn(level);
    }

    private static BlockPos findSafeInColumn(ServerLevel level, int x, int z) {
        if (x < MIN_POCKET_BLOCK || x > MAX_POCKET_BLOCK || z < MIN_POCKET_BLOCK || z > MAX_POCKET_BLOCK) {
            return null;
        }

        int minY = level.getMinBuildHeight() + 1;
        int maxY = level.getMaxBuildHeight() - 2;
        if (maxY < minY) {
            return null;
        }

        int preferredY = Mth.clamp(
                level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z),
                minY,
                maxY);
        int maxDelta = maxY - minY;
        for (int delta = 0; delta <= maxDelta; delta++) {
            int up = preferredY + delta;
            if (up <= maxY && isSafeStandingPosition(level, x, up, z)) {
                return new BlockPos(x, up, z);
            }
            int down = preferredY - delta;
            if (delta != 0 && down >= minY && isSafeStandingPosition(level, x, down, z)) {
                return new BlockPos(x, down, z);
            }
        }
        return null;
    }

    private static boolean isSafeStandingPosition(ServerLevel level, int x, int y, int z) {
        BlockPos feet = new BlockPos(x, y, z);
        BlockPos head = feet.above();
        BlockPos floor = feet.below();
        BlockState floorState = level.getBlockState(floor);
        return floorState.isFaceSturdy(level, floor, Direction.UP)
                && isPlayerSpaceClear(level, feet)
                && isPlayerSpaceClear(level, head);
    }

    private static boolean isPlayerSpaceClear(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
                && level.getFluidState(pos).isEmpty();
    }

    private static BlockPos buildEmergencySpawn(ServerLevel level) {
        int x = 0;
        int z = 0;
        int minY = level.getMinBuildHeight() + 1;
        int maxY = level.getMaxBuildHeight() - 2;
        int preferredY = Mth.clamp(64, minY, maxY);
        int y = findClearPairY(level, x, z, preferredY, minY, maxY);
        if (y == Integer.MIN_VALUE) {
            y = preferredY;
            level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(x, y + 1, z), Blocks.AIR.defaultBlockState());
        }

        // A 3x3 emergency platform is safer than a single block when an End-style
        // pocket produces no island beneath the center point.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                level.setBlockAndUpdate(new BlockPos(x + dx, y - 1, z + dz), Blocks.OBSIDIAN.defaultBlockState());
            }
        }
        return new BlockPos(x, y, z);
    }

    private static int findClearPairY(ServerLevel level, int x, int z, int preferredY, int minY, int maxY) {
        int maxDelta = maxY - minY;
        for (int delta = 0; delta <= maxDelta; delta++) {
            int up = preferredY + delta;
            if (up <= maxY
                    && isPlayerSpaceClear(level, new BlockPos(x, up, z))
                    && isPlayerSpaceClear(level, new BlockPos(x, up + 1, z))) {
                return up;
            }
            int down = preferredY - delta;
            if (delta != 0 && down >= minY
                    && isPlayerSpaceClear(level, new BlockPos(x, down, z))
                    && isPlayerSpaceClear(level, new BlockPos(x, down + 1, z))) {
                return down;
            }
        }
        return Integer.MIN_VALUE;
    }

    public static boolean isOwned(ResourceKey<Level> key) {
        return OWNED.containsKey(key) && isPocketKey(key);
    }

    public static void rememberDisconnect(ServerPlayer player) {
        ResourceKey<Level> dimension = player.getLevel().dimension();
        if (!isOwned(dimension)) {
            DISCONNECTED_PLAYERS.remove(player.getUUID());
            return;
        }

        DISCONNECTED_PLAYERS.put(player.getUUID(), new PocketReturnPoint(
                dimension,
                player.getX(),
                player.getY(),
                player.getZ(),
                player.getYRot(),
                player.getXRot()));
        BiomePockets.LOGGER.debug(
                "Reserved pocket {} for disconnected player {}",
                dimension.location(),
                player.getGameProfile().getName());
    }

    public static void restoreAfterLogin(ServerPlayer player) {
        UUID playerId = player.getUUID();
        MinecraftServer server = player.getServer();
        server.execute(() -> {
            PocketReturnPoint returnPoint = DISCONNECTED_PLAYERS.get(playerId);
            ServerPlayer currentPlayer = server.getPlayerList().getPlayer(playerId);
            if (returnPoint == null || currentPlayer == null) {
                return;
            }

            ServerLevel pocket = server.getLevel(returnPoint.dimension());
            if (pocket == null || !isOwned(returnPoint.dimension())) {
                DISCONNECTED_PLAYERS.remove(playerId);
                return;
            }

            currentPlayer.teleportTo(
                    pocket,
                    returnPoint.x(),
                    returnPoint.y(),
                    returnPoint.z(),
                    returnPoint.yRot(),
                    returnPoint.xRot());
            DISCONNECTED_PLAYERS.remove(playerId);
            BiomePockets.LOGGER.debug(
                    "Restored player {} to pocket {} after reconnect",
                    currentPlayer.getGameProfile().getName(),
                    returnPoint.dimension().location());
        });
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
            clearReturnReservations(key);
            return;
        }
        if (!level.players().isEmpty() || hasReturnReservation(key)) {
            return;
        }
        teardown(server, key, record, false);
    }

    private static boolean hasReturnReservation(ResourceKey<Level> key) {
        return DISCONNECTED_PLAYERS.values().stream()
                .anyMatch(returnPoint -> returnPoint.dimension().equals(key));
    }

    private static void clearReturnReservations(ResourceKey<Level> key) {
        DISCONNECTED_PLAYERS.entrySet().removeIf(entry -> entry.getValue().dimension().equals(key));
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
        DISCONNECTED_PLAYERS.clear();
    }

    @SuppressWarnings({"deprecation", "removal"})
    private static ServerLevel createLevel(MinecraftServer server, ResourceKey<Level> levelKey, Holder<Biome> biome) {
        Map<ResourceKey<Level>, ServerLevel> worlds = server.forgeGetWorldMap();
        if (worlds.containsKey(levelKey)) {
            throw new IllegalStateException("Pocket dimension already exists: " + levelKey.location());
        }

        Registry<StructureSet> structureSets = server.registryAccess().registryOrThrow(Registry.STRUCTURE_SET_REGISTRY);
        Registry<NormalNoise.NoiseParameters> noiseParameters = server.registryAccess().registryOrThrow(Registry.NOISE_REGISTRY);
        Registry<NoiseGeneratorSettings> noiseSettings = server.registryAccess().registryOrThrow(Registry.NOISE_GENERATOR_SETTINGS_REGISTRY);
        Registry<DimensionType> dimensionTypes = server.registryAccess().registryOrThrow(Registry.DIMENSION_TYPE_REGISTRY);

        GenerationProfile profile = generationProfile(biome);
        long seed = server.getWorldData().worldGenSettings().seed() ^ UUID.randomUUID().getMostSignificantBits();
        FixedBiomeSource biomeSource = new FixedBiomeSource(biome);
        BoundedNoiseBasedChunkGenerator generator = new BoundedNoiseBasedChunkGenerator(
                structureSets,
                noiseParameters,
                biomeSource,
                biome,
                seed,
                noiseSettings.getHolderOrThrow(profile.noiseSettings()),
                MIN_POCKET_CHUNK,
                MAX_POCKET_CHUNK
        );
        LevelStem stem = new LevelStem(dimensionTypes.getHolderOrThrow(profile.dimensionType()), generator);
        ResourceKey<LevelStem> stemKey = ResourceKey.create(Registry.LEVEL_STEM_REGISTRY, levelKey.location());

        String biomeName = biome.unwrapKey()
                .map(key -> key.location().toString())
                .orElse("<direct-biome>");
        BiomePockets.LOGGER.info(
                "Creating {} pocket for {} using {} noise and dimension type",
                levelKey.location(),
                biomeName,
                profile.name());

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

    @SuppressWarnings({"deprecation", "removal"})
    private static GenerationProfile generationProfile(Holder<Biome> biome) {
        boolean isNether = biome.is(BiomeTags.IS_NETHER);
        boolean isEnd = biome.is(Tags.Biomes.IS_END);

        Optional<ResourceKey<Biome>> biomeKey = biome.unwrapKey();
        if (biomeKey.isPresent()) {
            // BiomeDictionary is deprecated in favor of tags, but it remains useful
            // in 1.18.2 as a compatibility fallback for mods that registered their
            // dimension classification there instead of the newer Forge tags.
            isNether = isNether || BiomeDictionary.hasType(biomeKey.get(), BiomeDictionary.Type.NETHER);
            isEnd = isEnd || BiomeDictionary.hasType(biomeKey.get(), BiomeDictionary.Type.END);
        }

        if (isEnd) {
            return new GenerationProfile(
                    NoiseGeneratorSettings.END,
                    DimensionType.END_LOCATION,
                    "End");
        }
        if (isNether) {
            return new GenerationProfile(
                    NoiseGeneratorSettings.NETHER,
                    DimensionType.NETHER_LOCATION,
                    "Nether");
        }
        return new GenerationProfile(
                NoiseGeneratorSettings.OVERWORLD,
                DimensionType.OVERWORLD_LOCATION,
                "Overworld");
    }

    @SuppressWarnings("deprecation")
    private static void teardown(MinecraftServer server, ResourceKey<Level> key, PocketRecord record, boolean force) {
        if (!OWNED.containsKey(key) || !isPocketKey(key)) {
            return;
        }

        ServerLevel level = server.getLevel(key);
        if (level == null) {
            OWNED.remove(key);
            clearReturnReservations(key);
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
        clearReturnReservations(key);
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

    private record GenerationProfile(
            ResourceKey<NoiseGeneratorSettings> noiseSettings,
            ResourceKey<DimensionType> dimensionType,
            String name) { }

    private record PocketRecord(ResourceLocation biome, Path folder) { }

    private record PocketReturnPoint(
            ResourceKey<Level> dimension,
            double x,
            double y,
            double z,
            float yRot,
            float xRot) { }
}
