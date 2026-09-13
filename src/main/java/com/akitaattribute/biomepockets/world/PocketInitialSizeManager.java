package com.akitaattribute.biomepockets.world;

import com.akitaattribute.biomepockets.BiomePockets;
import com.mojang.datafixers.util.Either;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Creates new pockets with a weighted initial footprint while preserving the existing
 * BiomePockets lifetime, persistence, safety, and expansion systems.
 *
 * Initial-size weights:
 *   3x3 = 50%
 *   5x5 = 30%
 *   7x7 = 15%
 *   9x9 = 5%
 *
 * Initial size and purchased expansions are intentionally separate concepts. A fresh
 * permanent claim always starts with zero purchased expansions regardless of the size
 * rolled when its temporary pocket was first created.
 */
@Mod.EventBusSubscriber(modid = BiomePockets.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PocketInitialSizeManager {
    private static final String POCKET_PREFIX = "pocket_";
    private static final String MARKER_FILE = ".biomepockets-owned";
    private static final String GEOMETRY_FILE = ".biomepockets-geometry.properties";

    private static final TicketType<ResourceLocation> PREPARATION_TICKET = TicketType.create(
            "biomepockets_initial_prepare",
            Comparator.comparing(ResourceLocation::toString));
    private static final int PREPARATION_TICKET_RADIUS = 1;

    private PocketInitialSizeManager() { }

    /**
     * Entry point used by both transport items. The selected biome behavior remains
     * unchanged; only the generated playable radius is rolled before level creation.
     */
    public static void createAndTeleport(ServerPlayer player, ResourceLocation biomeId) {
        MinecraftServer server = player.getServer();
        Optional<Holder<Biome>> biome = BiomeCatalog.getBiome(server, biomeId);
        if (biome.isEmpty()) {
            return;
        }

        int radius = rollInitialRadius(player);
        int size = radius * 2 + 1;
        long seed = server.getWorldData().worldGenSettings().seed()
                ^ UUID.randomUUID().getMostSignificantBits();

        ResourceLocation dimensionId = new ResourceLocation(
                BiomePockets.MOD_ID,
                POCKET_PREFIX + UUID.randomUUID().toString().replace("-", ""));
        ResourceKey<Level> levelKey = ResourceKey.create(Registry.DIMENSION_REGISTRY, dimensionId);

        try {
            ServerLevel pocket = createLevelReflectively(server, levelKey, biome.get(), seed, radius);
            Path folder = pocketFolder(server, levelKey);
            writeOwnershipMarker(levelKey, folder);
            adoptOwnedPocket(levelKey, biomeId, folder);
            writePersistenceMetadataReflectively(pocket);

            long canonicalSeed = PocketSeedPersistence.ensureSeed(server, levelKey, seed);
            PocketClaimManager.writeGeometry(server, levelKey, radius, canonicalSeed);

            BiomePockets.LOGGER.info(
                    "Rolled new {} pocket {} at {}x{} (radius {}, roll weights 50/30/15/5)",
                    biomeId,
                    levelKey.location(),
                    size,
                    size,
                    radius);
            player.displayClientMessage(
                    new TextComponent("Preparing " + biomeId + " biome pocket (" + size + "x" + size + ")..."),
                    true);
            preparePocketAsync(server, pocket, levelKey, player.getUUID(), radius);
        } catch (Exception exception) {
            BiomePockets.LOGGER.error(
                    "Unable to create weighted biome pocket {} for {}",
                    dimensionId,
                    biomeId,
                    exception);
            if (PocketDimensionManager.isOwned(levelKey)) {
                PocketDimensionManager.teardownIfEmpty(server, levelKey);
            }
        }
    }

    /**
     * Preserve the existing manager action behavior but normalize a genuinely new
     * claim to zero purchased expansions after CLAIM succeeds. The pre-action claim
     * check prevents a forged/replayed CLAIM packet from resetting an existing claim's
     * expansion pricing.
     */
    public static void handleManagerAction(ServerPlayer player, PocketClaimManager.Action action) {
        boolean hadClaim = hasClaim(player.getUUID());
        PocketClaimManager.handleAction(player, action);

        if (action == PocketClaimManager.Action.CLAIM && !hadClaim) {
            resetFreshClaimExpansionCount(player);
        }
    }

    private static int rollInitialRadius(ServerPlayer player) {
        int roll = player.getRandom().nextInt(100);
        if (roll < 50) {
            return 1; // 3x3
        }
        if (roll < 80) {
            return 2; // 5x5
        }
        if (roll < 95) {
            return 3; // 7x7
        }
        return 4; // 9x9
    }

    private static void preparePocketAsync(
            MinecraftServer server,
            ServerLevel pocket,
            ResourceKey<Level> levelKey,
            UUID playerId,
            int radius) {
        ServerChunkCache chunkSource = pocket.getChunkSource();
        ResourceLocation ticketOwner = levelKey.location();
        int side = radius * 2 + 1;
        List<CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> futures =
                new ArrayList<>(side * side);

        // Submit the complete playable square up front so normal chunk/worldgen
        // executors can generate it in parallel. The player is not moved until every
        // playable chunk and the final containment pass have completed.
        for (int chunkX = -radius; chunkX <= radius; chunkX++) {
            for (int chunkZ = -radius; chunkZ <= radius; chunkZ++) {
                ChunkPos pos = new ChunkPos(chunkX, chunkZ);
                chunkSource.addRegionTicket(
                        PREPARATION_TICKET,
                        pos,
                        PREPARATION_TICKET_RADIUS,
                        ticketOwner);
                futures.add(chunkSource.getChunkFuture(chunkX, chunkZ, ChunkStatus.FULL, true));
            }
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0]))
                .whenComplete((ignored, throwable) -> server.execute(() -> {
                    if (server.getLevel(levelKey) != pocket || !PocketDimensionManager.isOwned(levelKey)) {
                        return;
                    }

                    if (throwable != null || !allSucceeded(futures)) {
                        BiomePockets.LOGGER.error(
                                "Pocket {} failed to finish its {} playable FULL chunks",
                                levelKey.location(),
                                side * side,
                                throwable);
                        failAndCleanup(server, pocket, levelKey, playerId, radius, ticketOwner,
                                "Biome pocket generation failed. See server log.");
                        return;
                    }

                    if (server.getPlayerList().getPlayer(playerId) == null) {
                        failAndCleanup(server, pocket, levelKey, playerId, radius, ticketOwner, null);
                        return;
                    }

                    finalizeContainmentAsync(
                            server,
                            pocket,
                            levelKey,
                            playerId,
                            radius,
                            ticketOwner);
                }));
    }

    private static void finalizeContainmentAsync(
            MinecraftServer server,
            ServerLevel pocket,
            ResourceKey<Level> levelKey,
            UUID playerId,
            int radius,
            ResourceLocation ticketOwner) {
        if (!(pocket.getChunkSource().getGenerator() instanceof BoundedNoiseBasedChunkGenerator generator)) {
            failAndCleanup(server, pocket, levelKey, playerId, radius, ticketOwner,
                    "Biome pocket containment failed. See server log.");
            return;
        }

        int barrierRadius = radius + 1;
        int expectedBarrierChunks = (barrierRadius * 2 + 1) * (barrierRadius * 2 + 1)
                - (radius * 2 + 1) * (radius * 2 + 1);
        List<CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> barrierFutures =
                new ArrayList<>(expectedBarrierChunks);
        ServerChunkCache chunkSource = pocket.getChunkSource();

        for (int chunkX = -barrierRadius; chunkX <= barrierRadius; chunkX++) {
            for (int chunkZ = -barrierRadius; chunkZ <= barrierRadius; chunkZ++) {
                if (Math.abs(chunkX) <= radius && Math.abs(chunkZ) <= radius) {
                    continue;
                }
                barrierFutures.add(chunkSource.getChunkFuture(
                        chunkX,
                        chunkZ,
                        ChunkStatus.FEATURES,
                        true));
            }
        }

        CompletableFuture.allOf(barrierFutures.toArray(new CompletableFuture<?>[0]))
                .whenComplete((ignored, throwable) -> server.execute(() -> {
                    if (server.getLevel(levelKey) != pocket || !PocketDimensionManager.isOwned(levelKey)) {
                        return;
                    }

                    if (throwable != null || !allSucceeded(barrierFutures)) {
                        BiomePockets.LOGGER.error(
                                "Pocket {} failed while preparing its {}-chunk containment ring",
                                levelKey.location(),
                                expectedBarrierChunks,
                                throwable);
                        failAndCleanup(server, pocket, levelKey, playerId, radius, ticketOwner,
                                "Biome pocket containment failed. See server log.");
                        return;
                    }

                    int repairedBlocks = 0;
                    for (CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> future
                            : barrierFutures) {
                        Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure> result = future.getNow(null);
                        if (result != null && result.left().isPresent()) {
                            repairedBlocks += generator.repairBarrierChunk(result.left().get());
                        }
                    }
                    int removedVines = generator.removeBarrierSupportedVines(pocket);
                    if (repairedBlocks > 0 || removedVines > 0) {
                        BiomePockets.LOGGER.info(
                                "Finalized weighted pocket {} containment: restored {} barrier blocks and removed {} wall vines",
                                levelKey.location(),
                                repairedBlocks,
                                removedVines);
                    }

                    ServerPlayer currentPlayer = server.getPlayerList().getPlayer(playerId);
                    if (currentPlayer == null) {
                        failAndCleanup(server, pocket, levelKey, playerId, radius, ticketOwner, null);
                        return;
                    }

                    try {
                        BlockPos spawn = findSafeSpawnReflectively(pocket);
                        currentPlayer.teleportTo(
                                pocket,
                                spawn.getX() + 0.5D,
                                spawn.getY(),
                                spawn.getZ() + 0.5D,
                                currentPlayer.getYRot(),
                                currentPlayer.getXRot());
                        removePreparationTickets(pocket, radius, ticketOwner);
                    } catch (ReflectiveOperationException exception) {
                        BiomePockets.LOGGER.error(
                                "Could not resolve spawn for weighted pocket {}",
                                levelKey.location(),
                                exception);
                        failAndCleanup(server, pocket, levelKey, playerId, radius, ticketOwner,
                                "Biome pocket spawn preparation failed. See server log.");
                    }
                }));
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

    private static void removePreparationTickets(
            ServerLevel pocket,
            int radius,
            ResourceLocation ticketOwner) {
        ServerChunkCache chunkSource = pocket.getChunkSource();
        for (int chunkX = -radius; chunkX <= radius; chunkX++) {
            for (int chunkZ = -radius; chunkZ <= radius; chunkZ++) {
                chunkSource.removeRegionTicket(
                        PREPARATION_TICKET,
                        new ChunkPos(chunkX, chunkZ),
                        PREPARATION_TICKET_RADIUS,
                        ticketOwner);
            }
        }
    }

    private static void failAndCleanup(
            MinecraftServer server,
            ServerLevel pocket,
            ResourceKey<Level> levelKey,
            UUID playerId,
            int radius,
            ResourceLocation ticketOwner,
            String message) {
        removePreparationTickets(pocket, radius, ticketOwner);
        if (message != null) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player != null) {
                player.displayClientMessage(new TextComponent(message), false);
            }
        }
        PocketDimensionManager.teardownIfEmpty(server, levelKey);
    }

    @SuppressWarnings("unchecked")
    private static ServerLevel createLevelReflectively(
            MinecraftServer server,
            ResourceKey<Level> levelKey,
            Holder<Biome> biome,
            long seed,
            int radius) throws ReflectiveOperationException {
        Method method = PocketExpansionManager.class.getDeclaredMethod(
                "createLevel",
                MinecraftServer.class,
                ResourceKey.class,
                Holder.class,
                long.class,
                int.class);
        method.setAccessible(true);
        return (ServerLevel) method.invoke(null, server, levelKey, biome, seed, radius);
    }

    private static BlockPos findSafeSpawnReflectively(ServerLevel pocket)
            throws ReflectiveOperationException {
        Method method = PocketDimensionManager.class.getDeclaredMethod("findSafeSpawn", ServerLevel.class);
        method.setAccessible(true);
        return (BlockPos) method.invoke(null, pocket);
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

    private static void writePersistenceMetadataReflectively(ServerLevel pocket)
            throws ReflectiveOperationException {
        Method method = PocketPersistenceManager.class.getDeclaredMethod("writePocketMetadata", ServerLevel.class);
        method.setAccessible(true);
        method.invoke(null, pocket);
    }

    private static Path pocketFolder(MinecraftServer server, ResourceKey<Level> dimension) {
        return DimensionType.getStorageFolder(dimension, server.getWorldPath(LevelResource.ROOT))
                .toAbsolutePath()
                .normalize();
    }

    private static boolean hasClaim(UUID playerId) {
        try {
            Field claimsField = PocketClaimManager.class.getDeclaredField("CLAIMS");
            claimsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UUID, Object> claims = (Map<UUID, Object>) claimsField.get(null);
            return claims.containsKey(playerId);
        } catch (ReflectiveOperationException exception) {
            BiomePockets.LOGGER.error("Could not inspect permanent-pocket claim state", exception);
            return true; // Fail closed: never reset an expansion counter when state is uncertain.
        }
    }

    private static void resetFreshClaimExpansionCount(ServerPlayer player) {
        try {
            Field claimsField = PocketClaimManager.class.getDeclaredField("CLAIMS");
            claimsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UUID, Object> claims = (Map<UUID, Object>) claimsField.get(null);
            Object claim = claims.get(player.getUUID());
            if (claim == null) {
                return;
            }

            Field expansionsField = claim.getClass().getDeclaredField("expansions");
            expansionsField.setAccessible(true);
            expansionsField.setInt(claim, 0);

            Method saveMethod = PocketClaimManager.class.getDeclaredMethod(
                    "saveClaim",
                    MinecraftServer.class,
                    claim.getClass());
            saveMethod.setAccessible(true);
            saveMethod.invoke(null, player.getServer(), claim);

            BiomePockets.LOGGER.info(
                    "Initialized fresh permanent pocket claim for {} with zero purchased expansions",
                    player.getGameProfile().getName());
        } catch (ReflectiveOperationException exception) {
            BiomePockets.LOGGER.error(
                    "Could not separate initial pocket size from expansion pricing for {}",
                    player.getGameProfile().getName(),
                    exception);
        }
    }

    /**
     * Persistence reconstructs every dynamic pocket using the legacy 3x3 creation
     * method. At the end of server startup, restore the actual saved generator radius
     * for all surviving pockets (claimed or temporary) from geometry metadata.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        @SuppressWarnings("deprecation")
        Map<ResourceKey<Level>, ServerLevel> worlds = server.forgeGetWorldMap();

        for (ServerLevel level : new ArrayList<>(worlds.values())) {
            ResourceKey<Level> dimension = level.dimension();
            if (!PocketDimensionManager.isOwned(dimension)
                    || !(level.getChunkSource().getGenerator() instanceof BoundedNoiseBasedChunkGenerator generator)) {
                continue;
            }

            Integer savedRadius = readSavedRadius(server, dimension);
            if (savedRadius == null || savedRadius < 1 || savedRadius == generator.getPocketRadius()) {
                continue;
            }

            generator.resizePocket(savedRadius);
            BiomePockets.LOGGER.info(
                    "Restored persisted pocket {} generator bounds to {}x{}",
                    dimension.location(),
                    savedRadius * 2 + 1,
                    savedRadius * 2 + 1);
        }
    }

    private static Integer readSavedRadius(MinecraftServer server, ResourceKey<Level> dimension) {
        Path folder = pocketFolder(server, dimension);
        if (!hasValidOwnershipMarker(dimension, folder)) {
            return null;
        }
        Path geometry = folder.resolve(GEOMETRY_FILE).normalize();
        if (!geometry.startsWith(folder) || !Files.isRegularFile(geometry)) {
            return null;
        }

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(geometry)) {
            properties.load(input);
            if (!dimension.location().toString().equals(properties.getProperty("dimension", ""))) {
                return null;
            }
            return Math.max(1, Integer.parseInt(properties.getProperty("radius", "1")));
        } catch (Exception exception) {
            BiomePockets.LOGGER.warn(
                    "Could not restore saved initial pocket radius for {}",
                    dimension.location(),
                    exception);
            return null;
        }
    }

    private static boolean hasValidOwnershipMarker(ResourceKey<Level> dimension, Path folder) {
        Path marker = folder.resolve(MARKER_FILE).normalize();
        try {
            return marker.startsWith(folder)
                    && Files.isRegularFile(marker)
                    && Files.readString(marker, StandardCharsets.UTF_8).trim()
                    .equals(dimension.location().toString());
        } catch (IOException exception) {
            return false;
        }
    }
}
