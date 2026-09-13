package com.akitaattribute.biomepockets.world;

import com.akitaattribute.biomepockets.BiomePockets;
import com.akitaattribute.biomepockets.network.NetworkHandler;
import com.mojang.datafixers.util.Either;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.ConfiguredStructureFeature;
import net.minecraft.world.level.levelgen.structure.StructureStart;
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
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Server-friendly permanent-pocket expansion.
 *
 * Expansion keeps the claimed dimension identity. A disposable same-seed staging
 * dimension generates only the newly unlocked playable ring and its new barrier ring.
 * Unlike the old implementation, generation, destination preparation, copying and
 * relighting are paced as individual work units with an idle server tick between units.
 * The Pocket Biome Manager receives exact completed/total work counts for its progress
 * bar; the manager item itself is never put on cooldown.
 */
@Mod.EventBusSubscriber(modid = BiomePockets.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PocketFriendlyExpansionManager {
    private static final String STAGING_PREFIX = "pocket_staging_";
    private static final String MARKER_FILE = ".biomepockets-owned";

    private static final TicketType<ResourceLocation> EXPANSION_HOLD_TICKET = TicketType.create(
            "biomepockets_friendly_expand_hold",
            Comparator.comparing(ResourceLocation::toString));
    private static final int TICKET_RADIUS = 1;
    private static final int QUIET_TICKS_BETWEEN_WORK = 1;

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

    private static final Deque<ExpansionJob> JOBS = new ArrayDeque<>();
    private static final Map<UUID, ExpansionJob> JOBS_BY_PLAYER = new HashMap<>();
    private static boolean requestInFlight;
    private static int quietTicksRemaining;
    private static Field activeStagingField;

    private PocketFriendlyExpansionManager() { }

    public static boolean startPlayerExpansion(ServerPlayer player) {
        return start(player, false);
    }

    public static boolean startAdminExpansion(ServerPlayer player) {
        return start(player, true);
    }

    /** Re-sends exact progress when the manager is opened during an active expansion. */
    public static void syncProgress(ServerPlayer player) {
        ExpansionJob job = JOBS_BY_PLAYER.get(player.getUUID());
        if (job != null && !job.cancelled) {
            sendProgress(job, true);
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean start(ServerPlayer player, boolean bypassXp) {
        UUID playerId = player.getUUID();
        if (JOBS_BY_PLAYER.containsKey(playerId)) {
            return false;
        }

        try {
            Field claimsField = PocketClaimManager.class.getDeclaredField("CLAIMS");
            Field expandingField = PocketClaimManager.class.getDeclaredField("EXPANDING");
            claimsField.setAccessible(true);
            expandingField.setAccessible(true);

            Map<UUID, Object> claims = (Map<UUID, Object>) claimsField.get(null);
            Set<UUID> expanding = (Set<UUID>) expandingField.get(null);
            Object claim = claims.get(playerId);
            if (claim == null) {
                player.displayClientMessage(
                        new TextComponent("You must claim a biome pocket before expanding it."),
                        false);
                NetworkHandler.updatePocketManager(player);
                return false;
            }
            if (expanding.contains(playerId)) {
                player.displayClientMessage(
                        new TextComponent("Your biome pocket is already expanding."),
                        false);
                syncProgress(player);
                return false;
            }

            Class<?> claimClass = claim.getClass();
            Field dimensionField = claimClass.getDeclaredField("dimension");
            Field biomeField = claimClass.getDeclaredField("biome");
            Field seedField = claimClass.getDeclaredField("seed");
            Field radiusField = claimClass.getDeclaredField("radius");
            Field expansionsField = claimClass.getDeclaredField("expansions");
            dimensionField.setAccessible(true);
            biomeField.setAccessible(true);
            seedField.setAccessible(true);
            radiusField.setAccessible(true);
            expansionsField.setAccessible(true);

            ResourceKey<Level> claimedDimension = (ResourceKey<Level>) dimensionField.get(claim);
            ResourceLocation biomeId = (ResourceLocation) biomeField.get(claim);
            long seed = seedField.getLong(claim);
            int oldRadius = Math.max(1, radiusField.getInt(claim));
            int completedExpansions = Math.max(0, expansionsField.getInt(claim));
            int newRadius = oldRadius + 1;
            int cost = bypassXp ? 0 : expansionCost(completedExpansions);

            if (!bypassXp && !player.getAbilities().instabuild && player.totalExperience < cost) {
                player.displayClientMessage(
                        new TextComponent("You need " + cost + " experience points to expand this pocket."),
                        false);
                NetworkHandler.updatePocketManager(player);
                return false;
            }

            ServerLevel target = player.getServer().getLevel(claimedDimension);
            if (target == null
                    || !(target.getChunkSource().getGenerator() instanceof BoundedNoiseBasedChunkGenerator)) {
                player.displayClientMessage(
                        new TextComponent("Expansion failed: claimed pocket is not currently available."),
                        false);
                NetworkHandler.updatePocketManager(player);
                return false;
            }

            Optional<Holder<Biome>> biome = BiomeCatalog.getBiome(player.getServer(), biomeId);
            if (biome.isEmpty()) {
                player.displayClientMessage(
                        new TextComponent("Expansion failed: biome is no longer registered."),
                        false);
                NetworkHandler.updatePocketManager(player);
                return false;
            }

            if (!expanding.add(playerId)) {
                return false;
            }

            MinecraftServer server = player.getServer();
            ResourceLocation stagingId = new ResourceLocation(
                    BiomePockets.MOD_ID,
                    STAGING_PREFIX + UUID.randomUUID().toString().replace("-", ""));
            ResourceKey<Level> stagingDimension = ResourceKey.create(Registry.DIMENSION_REGISTRY, stagingId);

            try {
                ServerLevel staging = createLevelReflectively(
                        server,
                        stagingDimension,
                        biome.get(),
                        seed,
                        newRadius);
                Path folder = pocketFolder(server, stagingDimension);
                writeOwnershipMarker(stagingDimension, folder);
                adoptOwnedPocket(stagingDimension, biomeId, folder);
                writePersistenceMetadataReflectively(staging);
                PocketClaimManager.writeGeometry(server, stagingDimension, newRadius, seed);
                markActiveStaging(stagingDimension, true);

                ExpansionJob job = new ExpansionJob(
                        server,
                        playerId,
                        claimedDimension,
                        stagingDimension,
                        target,
                        staging,
                        oldRadius,
                        newRadius,
                        seed,
                        cost,
                        ring(newRadius),
                        ring(newRadius + 1));
                JOBS.addLast(job);
                JOBS_BY_PLAYER.put(playerId, job);

                sendProgress(job, true);
                NetworkHandler.updatePocketManager(player);
                BiomePockets.LOGGER.info(
                        "Queued server-friendly in-place expansion {} from {}x{} to {}x{} using staging {} ({} work units)",
                        claimedDimension.location(),
                        oldRadius * 2 + 1,
                        oldRadius * 2 + 1,
                        newRadius * 2 + 1,
                        newRadius * 2 + 1,
                        stagingDimension.location(),
                        job.totalWork);
                return true;
            } catch (Exception exception) {
                expanding.remove(playerId);
                markActiveStaging(stagingDimension, false);
                BiomePockets.LOGGER.error(
                        "Could not create friendly expansion staging pocket {}",
                        stagingId,
                        exception);
                player.displayClientMessage(
                        new TextComponent("Biome pocket expansion failed while creating staging terrain."),
                        false);
                PocketDimensionManager.teardownIfEmpty(server, stagingDimension);
                NetworkHandler.sendExpansionProgress(player, 0, 0, false);
                NetworkHandler.updatePocketManager(player);
                return false;
            }
        } catch (ReflectiveOperationException exception) {
            BiomePockets.LOGGER.error("Could not inspect claimed pocket for expansion", exception);
            player.displayClientMessage(
                    new TextComponent("Biome pocket expansion failed. See server log."),
                    false);
            NetworkHandler.sendExpansionProgress(player, 0, 0, false);
            NetworkHandler.updatePocketManager(player);
            return false;
        }
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
            ExpansionJob job = JOBS.pollFirst();
            if (job == null) {
                return;
            }
            if (!isJobValid(job)) {
                cancel(job, null, null);
                continue;
            }

            JOBS.addLast(job);
            advanceOne(job);
            return;
        }
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        resetRuntimeState();
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        for (ExpansionJob job : new ArrayList<>(JOBS)) {
            job.cancelled = true;
            markActiveStaging(job.stagingDimension, false);
        }
        resetRuntimeState();
    }

    private static void resetRuntimeState() {
        JOBS.clear();
        JOBS_BY_PLAYER.clear();
        requestInFlight = false;
        quietTicksRemaining = 0;
    }

    private static boolean isJobValid(ExpansionJob job) {
        return !job.cancelled
                && job.server.getLevel(job.stagingDimension) == job.staging
                && job.server.getLevel(job.claimedDimension) == job.target
                && PocketDimensionManager.isOwned(job.stagingDimension)
                && job.server.getPlayerList().getPlayer(job.playerId) != null;
    }

    private static void advanceOne(ExpansionJob job) {
        job.normalizePhase();
        switch (job.phase) {
            case STAGING_PLAYABLE_PRE -> submitChunkStatus(
                    job,
                    job.playableRing.get(job.chunkIndex),
                    PRE_FEATURE_STATUSES.get(job.statusIndex),
                    false,
                    false);
            case STAGING_BARRIER_NOISE -> submitChunkStatus(
                    job,
                    job.barrierRing.get(job.chunkIndex),
                    ChunkStatus.NOISE,
                    false,
                    false);
            case STAGING_PLAYABLE_POST -> submitChunkStatus(
                    job,
                    job.playableRing.get(job.chunkIndex),
                    POST_FEATURE_STATUSES.get(job.statusIndex),
                    false,
                    job.statusIndex == POST_FEATURE_STATUSES.size() - 1);
            case STAGING_BARRIER_REPAIR -> submitChunkStatus(
                    job,
                    job.barrierRing.get(job.chunkIndex),
                    ChunkStatus.NOISE,
                    true,
                    false);
            case STAGING_BARRIER_FULL -> submitChunkStatus(
                    job,
                    job.barrierRing.get(job.chunkIndex),
                    ChunkStatus.FULL,
                    false,
                    true);
            case STAGING_VINES -> doVineCleanup(job);
            case STAGING_SNAPSHOT -> snapshotStagingChunk(job);
            case TARGET_FULL -> submitTargetFull(job);
            case TARGET_COPY -> copyTargetChunk(job);
            case TARGET_RESIZE -> resizeTarget(job);
            case TARGET_RELIGHT -> relightTargetChunk(job);
            case COMMIT -> commit(job);
            case FINISHED -> { }
        }
    }

    private static void submitChunkStatus(
            ExpansionJob job,
            ChunkPos pos,
            ChunkStatus status,
            boolean repairBarrier,
            boolean holdWhenComplete) {
        requestInFlight = true;
        CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> future =
                job.staging.getChunkSource().getChunkFuture(pos.x, pos.z, status, true);

        future.whenComplete((result, throwable) -> job.server.execute(() -> {
            requestInFlight = false;
            quietTicksRemaining = QUIET_TICKS_BETWEEN_WORK;

            if (job.cancelled || JOBS_BY_PLAYER.get(job.playerId) != job) {
                return;
            }
            if (!isJobValid(job)) {
                cancel(job, null, null);
                return;
            }
            if (throwable != null || result == null || result.left().isEmpty()) {
                cancel(job, "Biome pocket expansion staging generation failed.", throwable);
                return;
            }

            if (repairBarrier) {
                if (!(job.staging.getChunkSource().getGenerator()
                        instanceof BoundedNoiseBasedChunkGenerator generator)) {
                    cancel(job, "Biome pocket expansion staging generator became unavailable.", null);
                    return;
                }
                job.repairedBarrierBlocks += generator.repairBarrierChunk(result.left().get());
            }

            if (holdWhenComplete) {
                job.staging.getChunkSource().addRegionTicket(
                        EXPANSION_HOLD_TICKET,
                        pos,
                        TICKET_RADIUS,
                        job.ticketOwner);
                job.stagingHeld.add(pos);
            }

            job.completeIndexedWork();
            workCompleted(job);
        }));
    }

    private static void doVineCleanup(ExpansionJob job) {
        try {
            if (!(job.staging.getChunkSource().getGenerator()
                    instanceof BoundedNoiseBasedChunkGenerator generator)) {
                cancel(job, "Biome pocket expansion staging generator became unavailable.", null);
                return;
            }
            job.removedVines = generator.removeBarrierSupportedVines(job.staging);
            job.phase = Phase.STAGING_SNAPSHOT;
            job.chunkIndex = 0;
            workCompleted(job);
        } catch (Exception exception) {
            cancel(job, "Biome pocket expansion failed while finalizing staging containment.", exception);
        }
    }

    private static void snapshotStagingChunk(ExpansionJob job) {
        ChunkPos pos = job.affected.get(job.chunkIndex);
        try {
            job.stagedSnapshots.put(pos, snapshot(job.staging.getChunk(pos.x, pos.z)));
            job.chunkIndex++;
            if (job.chunkIndex >= job.affected.size()) {
                job.chunkIndex = 0;
                job.phase = Phase.TARGET_FULL;
            }
            workCompleted(job);
        } catch (Exception exception) {
            cancel(job, "Biome pocket expansion failed while snapshotting staging terrain.", exception);
        }
    }

    private static void submitTargetFull(ExpansionJob job) {
        ChunkPos pos = job.affected.get(job.chunkIndex);
        requestInFlight = true;
        CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> future =
                job.target.getChunkSource().getChunkFuture(pos.x, pos.z, ChunkStatus.FULL, true);

        future.whenComplete((result, throwable) -> job.server.execute(() -> {
            requestInFlight = false;
            quietTicksRemaining = QUIET_TICKS_BETWEEN_WORK;

            if (job.cancelled || JOBS_BY_PLAYER.get(job.playerId) != job) {
                return;
            }
            if (!isJobValid(job)) {
                cancel(job, null, null);
                return;
            }
            if (throwable != null || result == null || result.left().isEmpty()) {
                cancel(job, "Biome pocket expansion could not prepare the destination chunks.", throwable);
                return;
            }

            job.target.getChunkSource().addRegionTicket(
                    EXPANSION_HOLD_TICKET,
                    pos,
                    TICKET_RADIUS,
                    job.ticketOwner);
            job.targetHeld.add(pos);
            job.chunkIndex++;
            if (job.chunkIndex >= job.affected.size()) {
                job.chunkIndex = 0;
                job.phase = Phase.TARGET_COPY;
            }
            workCompleted(job);
        }));
    }

    private static void copyTargetChunk(ExpansionJob job) {
        if (job.chunkIndex == 0
                && !PocketClaimExpansionBridge.canCommit(
                        job.server,
                        job.playerId,
                        job.claimedDimension,
                        job.cost)) {
            cancel(job, "Biome pocket expansion was cancelled before modifying the claimed pocket.", null);
            return;
        }

        ChunkPos pos = job.affected.get(job.chunkIndex);
        try {
            LevelChunk targetChunk = job.target.getChunk(pos.x, pos.z);
            ChunkSnapshot source = job.stagedSnapshots.get(pos);
            if (source == null) {
                throw new IllegalStateException("Missing staging snapshot for " + pos);
            }
            job.rollbackSnapshots.put(pos, snapshot(targetChunk));
            applySnapshot(targetChunk, source);
            job.changedChunks.add(targetChunk);
            job.chunkIndex++;
            if (job.chunkIndex >= job.affected.size()) {
                job.chunkIndex = 0;
                job.phase = Phase.TARGET_RESIZE;
            }
            workCompleted(job);
        } catch (Exception exception) {
            cancel(job, "Biome pocket expansion failed while copying staging terrain.", exception);
        }
    }

    private static void resizeTarget(ExpansionJob job) {
        try {
            if (!(job.target.getChunkSource().getGenerator()
                    instanceof BoundedNoiseBasedChunkGenerator generator)) {
                cancel(job, "Biome pocket expansion destination generator is unavailable.", null);
                return;
            }
            generator.resizePocket(job.newRadius);
            job.targetGenerator = generator;
            job.targetResized = true;
            job.phase = Phase.TARGET_RELIGHT;
            job.chunkIndex = 0;
            workCompleted(job);
        } catch (Exception exception) {
            cancel(job, "Biome pocket expansion failed while resizing the claimed pocket.", exception);
        }
    }

    private static void relightTargetChunk(ExpansionJob job) {
        LevelChunk chunk = job.changedChunks.get(job.chunkIndex);
        requestInFlight = true;
        chunk.setLightCorrect(false);
        CompletableFuture<ChunkAccess> future = job.target.getChunkSource().getLightEngine().lightChunk(chunk, false);
        future.whenComplete((ignored, throwable) -> job.server.execute(() -> {
            requestInFlight = false;
            quietTicksRemaining = QUIET_TICKS_BETWEEN_WORK;

            if (job.cancelled || JOBS_BY_PLAYER.get(job.playerId) != job) {
                return;
            }
            if (throwable != null) {
                cancel(job, "Biome pocket expansion failed while lighting copied terrain.", throwable);
                return;
            }

            refreshClientChunk(job.target, chunk);
            job.chunkIndex++;
            if (job.chunkIndex >= job.changedChunks.size()) {
                job.chunkIndex = 0;
                job.phase = Phase.COMMIT;
            }
            workCompleted(job);
        }));
    }

    private static void commit(ExpansionJob job) {
        if (!PocketClaimExpansionBridge.canCommit(
                job.server,
                job.playerId,
                job.claimedDimension,
                job.cost)) {
            cancel(job, "Biome pocket expansion was cancelled before saving the expanded claim.", null);
            return;
        }

        if (!PocketClaimExpansionBridge.commit(
                job.server,
                job.playerId,
                job.claimedDimension,
                job.newRadius,
                job.seed,
                job.cost)) {
            cancel(job, "Biome pocket expansion could not save the expanded claim.", null);
            return;
        }

        job.completedWork++;
        sendProgress(job, true);
        finish(job);
    }

    private static void workCompleted(ExpansionJob job) {
        job.completedWork++;
        sendProgress(job, true);
        quietTicksRemaining = QUIET_TICKS_BETWEEN_WORK;
    }

    private static void finish(ExpansionJob job) {
        job.phase = Phase.FINISHED;
        removeJob(job);
        releaseTickets(job);
        markActiveStaging(job.stagingDimension, false);
        PocketDimensionManager.teardownIfEmpty(job.server, job.stagingDimension);

        ServerPlayer player = job.server.getPlayerList().getPlayer(job.playerId);
        if (player != null) {
            NetworkHandler.sendExpansionProgress(player, job.totalWork, job.totalWork, false);
            NetworkHandler.updatePocketManager(player);
        }

        BiomePockets.LOGGER.info(
                "Completed server-friendly in-place expansion {} from {}x{} to {}x{} using staging {}; repaired {} barrier blocks and removed {} wall vines",
                job.claimedDimension.location(),
                job.oldRadius * 2 + 1,
                job.oldRadius * 2 + 1,
                job.newRadius * 2 + 1,
                job.newRadius * 2 + 1,
                job.stagingDimension.location(),
                job.repairedBarrierBlocks,
                job.removedVines);
    }

    private static void cancel(ExpansionJob job, String message, Throwable throwable) {
        if (job.cancelled) {
            return;
        }
        job.cancelled = true;

        if (throwable != null) {
            BiomePockets.LOGGER.error(message == null ? "Biome pocket expansion failed" : message, throwable);
        }

        rollbackTarget(job);
        removeJob(job);
        releaseTickets(job);
        markActiveStaging(job.stagingDimension, false);
        PocketClaimManager.expansionFailed(
                job.server,
                job.playerId,
                message == null ? "Biome pocket expansion was cancelled." : message);
        PocketDimensionManager.teardownIfEmpty(job.server, job.stagingDimension);

        ServerPlayer player = job.server.getPlayerList().getPlayer(job.playerId);
        if (player != null) {
            NetworkHandler.sendExpansionProgress(player, 0, 0, false);
            NetworkHandler.updatePocketManager(player);
        }
    }

    private static void rollbackTarget(ExpansionJob job) {
        if (job.rollbackSnapshots.isEmpty()) {
            return;
        }
        try {
            for (Map.Entry<ChunkPos, ChunkSnapshot> entry : job.rollbackSnapshots.entrySet()) {
                ChunkPos pos = entry.getKey();
                applySnapshot(job.target.getChunk(pos.x, pos.z), entry.getValue());
            }
            if (job.target.getChunkSource().getGenerator() instanceof BoundedNoiseBasedChunkGenerator generator) {
                generator.resizePocket(job.oldRadius);
            }
            for (ChunkPos pos : job.rollbackSnapshots.keySet()) {
                try {
                    LevelChunk chunk = job.target.getChunk(pos.x, pos.z);
                    chunk.setLightCorrect(false);
                    job.target.getChunkSource().getLightEngine().lightChunk(chunk, false);
                } catch (Exception exception) {
                    BiomePockets.LOGGER.warn("Could not relight rolled-back expansion chunk {}", pos, exception);
                }
            }
        } catch (Exception exception) {
            BiomePockets.LOGGER.error(
                    "Could not fully roll back failed in-place expansion {}",
                    job.claimedDimension.location(),
                    exception);
        }
    }

    private static void releaseTickets(ExpansionJob job) {
        for (ChunkPos pos : job.stagingHeld) {
            job.staging.getChunkSource().removeRegionTicket(
                    EXPANSION_HOLD_TICKET,
                    pos,
                    TICKET_RADIUS,
                    job.ticketOwner);
        }
        for (ChunkPos pos : job.targetHeld) {
            job.target.getChunkSource().removeRegionTicket(
                    EXPANSION_HOLD_TICKET,
                    pos,
                    TICKET_RADIUS,
                    job.ticketOwner);
        }
        job.stagingHeld.clear();
        job.targetHeld.clear();
    }

    private static void removeJob(ExpansionJob job) {
        JOBS.remove(job);
        JOBS_BY_PLAYER.remove(job.playerId, job);
    }

    private static void sendProgress(ExpansionJob job, boolean active) {
        ServerPlayer player = job.server.getPlayerList().getPlayer(job.playerId);
        if (player != null) {
            NetworkHandler.sendExpansionProgress(
                    player,
                    Math.min(job.completedWork, job.totalWork),
                    job.totalWork,
                    active);
        }
    }

    private static int expansionCost(int completedExpansions) {
        long multiplier = (long) Math.max(0, completedExpansions) + 1L;
        return (int) Math.min(Integer.MAX_VALUE, PocketClaimManager.BASE_EXPANSION_XP * multiplier);
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

    private static void refreshClientChunk(ServerLevel level, LevelChunk chunk) {
        if (level.players().isEmpty()) {
            return;
        }
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
        if (destinationSections.length != snapshot.sections.length) {
            throw new IllegalStateException("Chunk section count differs between staging and claimed pocket");
        }

        target.clearAllBlockEntities();
        for (int i = 0; i < destinationSections.length; i++) {
            LevelChunkSection section = snapshot.sections[i];
            destinationSections[i] = new LevelChunkSection(
                    section.bottomBlockY(),
                    section.getStates().copy(),
                    section.getBiomes().copy());
        }

        for (Map.Entry<Heightmap.Types, long[]> entry : snapshot.heightmaps.entrySet()) {
            target.setHeightmap(entry.getKey(), entry.getValue().clone());
        }
        target.setAllStarts(new HashMap<>(snapshot.starts));

        Map<ConfiguredStructureFeature<?, ?>, LongSet> references = new HashMap<>();
        for (Map.Entry<ConfiguredStructureFeature<?, ?>, LongSet> entry : snapshot.references.entrySet()) {
            references.put(entry.getKey(), new LongOpenHashSet(entry.getValue()));
        }
        target.setAllReferences(references);

        for (Map.Entry<BlockPos, CompoundTag> entry : snapshot.blockEntities.entrySet()) {
            target.setBlockEntityNbt(entry.getValue().copy());
            target.getBlockEntity(entry.getKey(), LevelChunk.EntityCreationType.IMMEDIATE);
        }

        target.setInhabitedTime(snapshot.inhabitedTime);
        target.setLightCorrect(false);
        target.setUnsaved(true);
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

    @SuppressWarnings("unchecked")
    private static void markActiveStaging(ResourceKey<Level> dimension, boolean active) {
        try {
            if (activeStagingField == null) {
                activeStagingField = PocketExpansionManager.class.getDeclaredField("ACTIVE_STAGING");
                activeStagingField.setAccessible(true);
            }
            Set<ResourceKey<Level>> activeSet =
                    (Set<ResourceKey<Level>>) activeStagingField.get(null);
            if (active) {
                activeSet.add(dimension);
            } else {
                activeSet.remove(dimension);
            }
        } catch (ReflectiveOperationException exception) {
            BiomePockets.LOGGER.error("Could not update expansion staging protection", exception);
        }
    }

    private enum Phase {
        STAGING_PLAYABLE_PRE,
        STAGING_BARRIER_NOISE,
        STAGING_PLAYABLE_POST,
        STAGING_BARRIER_REPAIR,
        STAGING_BARRIER_FULL,
        STAGING_VINES,
        STAGING_SNAPSHOT,
        TARGET_FULL,
        TARGET_COPY,
        TARGET_RESIZE,
        TARGET_RELIGHT,
        COMMIT,
        FINISHED
    }

    private static final class ExpansionJob {
        private final MinecraftServer server;
        private final UUID playerId;
        private final ResourceKey<Level> claimedDimension;
        private final ResourceKey<Level> stagingDimension;
        private final ServerLevel target;
        private final ServerLevel staging;
        private final int oldRadius;
        private final int newRadius;
        private final long seed;
        private final int cost;
        private final List<ChunkPos> playableRing;
        private final List<ChunkPos> barrierRing;
        private final List<ChunkPos> affected;
        private final ResourceLocation ticketOwner;
        private final int totalWork;

        private final Map<ChunkPos, ChunkSnapshot> stagedSnapshots = new LinkedHashMap<>();
        private final Map<ChunkPos, ChunkSnapshot> rollbackSnapshots = new LinkedHashMap<>();
        private final List<LevelChunk> changedChunks = new ArrayList<>();
        private final Set<ChunkPos> stagingHeld = new java.util.HashSet<>();
        private final Set<ChunkPos> targetHeld = new java.util.HashSet<>();

        private Phase phase = Phase.STAGING_PLAYABLE_PRE;
        private int statusIndex;
        private int chunkIndex;
        private int completedWork;
        private int repairedBarrierBlocks;
        private int removedVines;
        private boolean targetResized;
        private boolean cancelled;
        private BoundedNoiseBasedChunkGenerator targetGenerator;

        private ExpansionJob(
                MinecraftServer server,
                UUID playerId,
                ResourceKey<Level> claimedDimension,
                ResourceKey<Level> stagingDimension,
                ServerLevel target,
                ServerLevel staging,
                int oldRadius,
                int newRadius,
                long seed,
                int cost,
                List<ChunkPos> playableRing,
                List<ChunkPos> barrierRing) {
            this.server = server;
            this.playerId = playerId;
            this.claimedDimension = claimedDimension;
            this.stagingDimension = stagingDimension;
            this.target = target;
            this.staging = staging;
            this.oldRadius = oldRadius;
            this.newRadius = newRadius;
            this.seed = seed;
            this.cost = cost;
            this.playableRing = playableRing;
            this.barrierRing = barrierRing;
            this.affected = new ArrayList<>(playableRing.size() + barrierRing.size());
            this.affected.addAll(playableRing);
            this.affected.addAll(barrierRing);
            this.ticketOwner = stagingDimension.location();
            this.totalWork = playableRing.size() * (PRE_FEATURE_STATUSES.size() + POST_FEATURE_STATUSES.size())
                    + barrierRing.size() * 3
                    + affected.size() * 4
                    + 3;
        }

        private void completeIndexedWork() {
            chunkIndex++;
            normalizePhase();
        }

        private void normalizePhase() {
            boolean changed;
            do {
                changed = false;
                switch (phase) {
                    case STAGING_PLAYABLE_PRE -> {
                        if (chunkIndex >= playableRing.size()) {
                            chunkIndex = 0;
                            statusIndex++;
                            if (statusIndex >= PRE_FEATURE_STATUSES.size()) {
                                statusIndex = 0;
                                phase = Phase.STAGING_BARRIER_NOISE;
                            }
                            changed = true;
                        }
                    }
                    case STAGING_BARRIER_NOISE -> {
                        if (chunkIndex >= barrierRing.size()) {
                            chunkIndex = 0;
                            phase = Phase.STAGING_PLAYABLE_POST;
                            changed = true;
                        }
                    }
                    case STAGING_PLAYABLE_POST -> {
                        if (chunkIndex >= playableRing.size()) {
                            chunkIndex = 0;
                            statusIndex++;
                            if (statusIndex >= POST_FEATURE_STATUSES.size()) {
                                statusIndex = 0;
                                phase = Phase.STAGING_BARRIER_REPAIR;
                            }
                            changed = true;
                        }
                    }
                    case STAGING_BARRIER_REPAIR -> {
                        if (chunkIndex >= barrierRing.size()) {
                            chunkIndex = 0;
                            phase = Phase.STAGING_BARRIER_FULL;
                            changed = true;
                        }
                    }
                    case STAGING_BARRIER_FULL -> {
                        if (chunkIndex >= barrierRing.size()) {
                            chunkIndex = 0;
                            phase = Phase.STAGING_VINES;
                            changed = true;
                        }
                    }
                    default -> { }
                }
            } while (changed);
        }
    }

    private record ChunkSnapshot(
            LevelChunkSection[] sections,
            Map<BlockPos, CompoundTag> blockEntities,
            Map<Heightmap.Types, long[]> heightmaps,
            Map<ConfiguredStructureFeature<?, ?>, StructureStart> starts,
            Map<ConfiguredStructureFeature<?, ?>, LongSet> references,
            long inhabitedTime) { }
}
