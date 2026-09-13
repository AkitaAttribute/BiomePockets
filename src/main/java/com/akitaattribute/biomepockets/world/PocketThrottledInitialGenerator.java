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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Creates new pockets without flooding Minecraft's world-generation pipeline.
 *
 * The previous implementation submitted every playable chunk at FULL at once. Even a
 * 3x3 pocket could therefore schedule a large dependency graph concurrently and starve
 * the live server while worldgen caught up. This scheduler advances generation one
 * ChunkStatus request at a time globally and leaves one full server tick idle between
 * requests. Multiple players share the same throttle rather than multiplying load.
 *
 * The player is still not teleported until all playable chunks are FULL and the outer
 * barrier ring has been repaired. Preparation takes longer by design; normal gameplay
 * on the server gets priority over pocket completion speed.
 */
@Mod.EventBusSubscriber(modid = BiomePockets.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PocketThrottledInitialGenerator {
    private static final String POCKET_PREFIX = "pocket_";
    private static final String MARKER_FILE = ".biomepockets-owned";

    private static final List<ChunkStatus> PRE_FEATURE_STATUSES = List.of(
            ChunkStatus.STRUCTURE_STARTS,
            ChunkStatus.STRUCTURE_REFERENCES,
            ChunkStatus.BIOMES,
            ChunkStatus.NOISE,
            ChunkStatus.SURFACE,
            ChunkStatus.CARVERS,
            ChunkStatus.LIQUID_CARVERS);

    private static final List<ChunkStatus> POST_FEATURE_STATUSES = List.of(
            ChunkStatus.FEATURES,
            ChunkStatus.LIGHT,
            ChunkStatus.SPAWN,
            ChunkStatus.HEIGHTMAPS,
            ChunkStatus.FULL);

    /** One completely idle tick after each generation request completes. */
    private static final int QUIET_TICKS_BETWEEN_REQUESTS = 1;

    private static final Deque<GenerationJob> JOBS = new ArrayDeque<>();
    private static final Map<UUID, GenerationJob> JOBS_BY_PLAYER = new HashMap<>();
    private static boolean requestInFlight;
    private static int quietTicksRemaining;

    private PocketThrottledInitialGenerator() { }

    public static void createAndTeleport(ServerPlayer player, ResourceLocation biomeId) {
        if (JOBS_BY_PLAYER.containsKey(player.getUUID())) {
            player.displayClientMessage(
                    new TextComponent("A biome pocket is already being prepared for you."),
                    false);
            return;
        }

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

            GenerationJob job = new GenerationJob(
                    server,
                    pocket,
                    levelKey,
                    player.getUUID(),
                    radius,
                    playablePositions(radius),
                    barrierPositions(radius));
            JOBS.addLast(job);
            JOBS_BY_PLAYER.put(player.getUUID(), job);

            BiomePockets.LOGGER.info(
                    "Queued server-friendly {} pocket {} at {}x{} ({} generation operations)",
                    biomeId,
                    levelKey.location(),
                    size,
                    size,
                    job.totalOperations());
            player.displayClientMessage(
                    new TextComponent("Preparing " + biomeId + " biome pocket (" + size + "x" + size
                            + ") at server-friendly speed..."),
                    true);
        } catch (Exception exception) {
            BiomePockets.LOGGER.error(
                    "Unable to create throttled biome pocket {} for {}",
                    dimensionId,
                    biomeId,
                    exception);
            if (PocketDimensionManager.isOwned(levelKey)) {
                PocketDimensionManager.teardownIfEmpty(server, levelKey);
            }
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

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || requestInFlight || JOBS.isEmpty()) {
            return;
        }

        if (quietTicksRemaining > 0) {
            quietTicksRemaining--;
            return;
        }

        int jobsToCheck = JOBS.size();
        while (jobsToCheck-- > 0) {
            GenerationJob job = JOBS.pollFirst();
            if (job == null) {
                return;
            }

            if (!isJobValid(job)) {
                cancelJob(job, null);
                continue;
            }

            // Round-robin queued players. The global in-flight guard still guarantees
            // that only one pocket worldgen request exists at a time.
            JOBS.addLast(job);
            submitNextOperation(job);
            return;
        }
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        resetRuntimeState();
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        for (GenerationJob job : JOBS) {
            job.cancelled = true;
        }
        resetRuntimeState();
    }

    private static void resetRuntimeState() {
        JOBS.clear();
        JOBS_BY_PLAYER.clear();
        requestInFlight = false;
        quietTicksRemaining = 0;
    }

    private static boolean isJobValid(GenerationJob job) {
        return !job.cancelled
                && job.server.getLevel(job.levelKey) == job.pocket
                && PocketDimensionManager.isOwned(job.levelKey)
                && job.server.getPlayerList().getPlayer(job.playerId) != null;
    }

    private static void submitNextOperation(GenerationJob job) {
        GenerationRequest request = job.nextRequest();
        if (request == null) {
            finishJob(job);
            return;
        }

        requestInFlight = true;
        long startedNanos = System.nanoTime();
        CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> future =
                job.pocket.getChunkSource().getChunkFuture(
                        request.pos().x,
                        request.pos().z,
                        request.status(),
                        true);

        future.whenComplete((result, throwable) -> job.server.execute(() -> {
            requestInFlight = false;
            quietTicksRemaining = QUIET_TICKS_BETWEEN_REQUESTS;

            if (job.cancelled || JOBS_BY_PLAYER.get(job.playerId) != job) {
                return;
            }
            if (!isJobValid(job)) {
                cancelJob(job, null);
                return;
            }
            if (throwable != null || result == null || result.left().isEmpty()) {
                BiomePockets.LOGGER.error(
                        "Pocket {} failed throttled generation at {} for chunk {}",
                        job.levelKey.location(),
                        request.status(),
                        request.pos(),
                        throwable);
                cancelJob(job, "Biome pocket generation failed. See server log.");
                return;
            }

            if (request.repairBarrier()) {
                if (!(job.pocket.getChunkSource().getGenerator() instanceof BoundedNoiseBasedChunkGenerator generator)) {
                    cancelJob(job, "Biome pocket containment failed. See server log.");
                    return;
                }
                job.repairedBarrierBlocks += generator.repairBarrierChunk(result.left().get());
            }

            job.completeRequest();
            long elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000L;
            if (elapsedMillis >= 100L) {
                BiomePockets.LOGGER.debug(
                        "Throttled pocket {} operation {} at {} took {} ms",
                        job.levelKey.location(),
                        request.status(),
                        request.pos(),
                        elapsedMillis);
            }
            maybeReportProgress(job);
        }));
    }

    private static void maybeReportProgress(GenerationJob job) {
        int completed = job.completedOperations();
        int total = job.totalOperations();
        if (completed == total || completed % Math.max(8, total / 10) == 0) {
            ServerPlayer player = job.server.getPlayerList().getPlayer(job.playerId);
            if (player != null) {
                player.displayClientMessage(
                        new TextComponent("Preparing biome pocket: " + completed + "/" + total),
                        true);
            }
        }
    }

    private static void finishJob(GenerationJob job) {
        if (!isJobValid(job)) {
            cancelJob(job, null);
            return;
        }
        if (!(job.pocket.getChunkSource().getGenerator() instanceof BoundedNoiseBasedChunkGenerator generator)) {
            cancelJob(job, "Biome pocket containment failed. See server log.");
            return;
        }

        // This scan is intentionally left until all generation has finished. Barrier
        // chunk repair has already been spread across individual server ticks.
        int removedVines = generator.removeBarrierSupportedVines(job.pocket);
        if (job.repairedBarrierBlocks > 0 || removedVines > 0) {
            BiomePockets.LOGGER.info(
                    "Finalized throttled pocket {} containment: restored {} barrier blocks and removed {} wall vines",
                    job.levelKey.location(),
                    job.repairedBarrierBlocks,
                    removedVines);
        }

        ServerPlayer player = job.server.getPlayerList().getPlayer(job.playerId);
        if (player == null) {
            cancelJob(job, null);
            return;
        }

        try {
            BlockPos spawn = findSafeSpawnReflectively(job.pocket);
            player.teleportTo(
                    job.pocket,
                    spawn.getX() + 0.5D,
                    spawn.getY(),
                    spawn.getZ() + 0.5D,
                    player.getYRot(),
                    player.getXRot());
            removeJob(job);
        } catch (ReflectiveOperationException exception) {
            BiomePockets.LOGGER.error(
                    "Could not resolve spawn for throttled pocket {}",
                    job.levelKey.location(),
                    exception);
            cancelJob(job, "Biome pocket spawn preparation failed. See server log.");
        }
    }

    private static void cancelJob(GenerationJob job, String message) {
        job.cancelled = true;
        removeJob(job);

        if (message != null) {
            ServerPlayer player = job.server.getPlayerList().getPlayer(job.playerId);
            if (player != null) {
                player.displayClientMessage(new TextComponent(message), false);
            }
        }

        if (PocketDimensionManager.isOwned(job.levelKey)) {
            PocketDimensionManager.teardownIfEmpty(job.server, job.levelKey);
        }
    }

    private static void removeJob(GenerationJob job) {
        JOBS.remove(job);
        JOBS_BY_PLAYER.remove(job.playerId, job);
    }

    private static List<ChunkPos> playablePositions(int radius) {
        List<ChunkPos> result = new ArrayList<>((radius * 2 + 1) * (radius * 2 + 1));
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                result.add(new ChunkPos(x, z));
            }
        }

        // Center-out ordering gets the eventual spawn neighborhood ready first and
        // maximizes reuse of neighboring chunk-status dependencies.
        result.sort(Comparator
                .comparingInt((ChunkPos pos) -> Math.max(Math.abs(pos.x), Math.abs(pos.z)))
                .thenComparingInt(pos -> Math.abs(pos.x) + Math.abs(pos.z))
                .thenComparingInt(pos -> pos.x)
                .thenComparingInt(pos -> pos.z));
        return result;
    }

    private static List<ChunkPos> barrierPositions(int radius) {
        int outer = radius + 1;
        List<ChunkPos> result = new ArrayList<>(outer * 8);
        for (int x = -outer; x <= outer; x++) {
            for (int z = -outer; z <= outer; z++) {
                if (Math.max(Math.abs(x), Math.abs(z)) == outer) {
                    result.add(new ChunkPos(x, z));
                }
            }
        }
        return result;
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

    private enum Phase {
        PLAYABLE_PRE_FEATURES,
        BARRIER_NOISE,
        PLAYABLE_POST_FEATURES,
        BARRIER_REPAIR,
        FINISHED
    }

    private record GenerationRequest(ChunkPos pos, ChunkStatus status, boolean repairBarrier) { }

    private static final class GenerationJob {
        private final MinecraftServer server;
        private final ServerLevel pocket;
        private final ResourceKey<Level> levelKey;
        private final UUID playerId;
        private final int radius;
        private final List<ChunkPos> playable;
        private final List<ChunkPos> barrier;

        private Phase phase = Phase.PLAYABLE_PRE_FEATURES;
        private int statusIndex;
        private int chunkIndex;
        private int completedOperations;
        private int repairedBarrierBlocks;
        private boolean cancelled;

        private GenerationJob(
                MinecraftServer server,
                ServerLevel pocket,
                ResourceKey<Level> levelKey,
                UUID playerId,
                int radius,
                List<ChunkPos> playable,
                List<ChunkPos> barrier) {
            this.server = server;
            this.pocket = pocket;
            this.levelKey = levelKey;
            this.playerId = playerId;
            this.radius = radius;
            this.playable = playable;
            this.barrier = barrier;
        }

        private GenerationRequest nextRequest() {
            normalizePhase();
            return switch (phase) {
                case PLAYABLE_PRE_FEATURES -> new GenerationRequest(
                        playable.get(chunkIndex),
                        PRE_FEATURE_STATUSES.get(statusIndex),
                        false);
                case BARRIER_NOISE -> new GenerationRequest(
                        barrier.get(chunkIndex),
                        ChunkStatus.NOISE,
                        false);
                case PLAYABLE_POST_FEATURES -> new GenerationRequest(
                        playable.get(chunkIndex),
                        POST_FEATURE_STATUSES.get(statusIndex),
                        false);
                case BARRIER_REPAIR -> new GenerationRequest(
                        barrier.get(chunkIndex),
                        ChunkStatus.NOISE,
                        true);
                case FINISHED -> null;
            };
        }

        private void completeRequest() {
            completedOperations++;
            chunkIndex++;
            normalizePhase();
        }

        private void normalizePhase() {
            boolean changed;
            do {
                changed = false;
                switch (phase) {
                    case PLAYABLE_PRE_FEATURES -> {
                        if (chunkIndex >= playable.size()) {
                            chunkIndex = 0;
                            statusIndex++;
                            if (statusIndex >= PRE_FEATURE_STATUSES.size()) {
                                statusIndex = 0;
                                phase = Phase.BARRIER_NOISE;
                            }
                            changed = true;
                        }
                    }
                    case BARRIER_NOISE -> {
                        if (chunkIndex >= barrier.size()) {
                            chunkIndex = 0;
                            phase = Phase.PLAYABLE_POST_FEATURES;
                            changed = true;
                        }
                    }
                    case PLAYABLE_POST_FEATURES -> {
                        if (chunkIndex >= playable.size()) {
                            chunkIndex = 0;
                            statusIndex++;
                            if (statusIndex >= POST_FEATURE_STATUSES.size()) {
                                statusIndex = 0;
                                phase = Phase.BARRIER_REPAIR;
                            }
                            changed = true;
                        }
                    }
                    case BARRIER_REPAIR -> {
                        if (chunkIndex >= barrier.size()) {
                            chunkIndex = 0;
                            phase = Phase.FINISHED;
                            changed = true;
                        }
                    }
                    case FINISHED -> { }
                }
            } while (changed && phase != Phase.FINISHED);
        }

        private int totalOperations() {
            return playable.size() * (PRE_FEATURE_STATUSES.size() + POST_FEATURE_STATUSES.size())
                    + barrier.size() * 2;
        }

        private int completedOperations() {
            return completedOperations;
        }
    }
}
