package com.akitaattribute.biomepockets.world;

import com.akitaattribute.biomepockets.BiomePockets;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Event-driven cleanup for temporary pockets.
 *
 * Forge can report a dimension change before the departing ServerPlayer has disappeared
 * from the old ServerLevel's player list. The old one-shot cleanup therefore had a race:
 * teardownIfEmpty() saw one player, returned, and nothing ever retried. This class keeps
 * only dimensions that have actually received a departure request in a short bounded
 * retry queue. It never scans every loaded pocket every tick.
 */
@Mod.EventBusSubscriber(modid = BiomePockets.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PocketDepartureCleanup {
    private static final int MAX_GHOST_RETRIES = 40;

    private static final Map<ResourceKey<Level>, PendingCleanup> PENDING = new HashMap<>();
    private static final Map<ResourceKey<Level>, CleanupTrace> LAST_TRACE = new HashMap<>();

    private PocketDepartureCleanup() { }

    public static void requestCleanup(
            MinecraftServer server,
            ResourceKey<Level> dimension,
            String cause,
            UUID playerId) {
        if (!PocketDimensionManager.isOwned(dimension)) {
            return;
        }

        PendingCleanup existing = PENDING.get(dimension);
        if (existing == null || existing.server() != server) {
            PENDING.put(dimension, new PendingCleanup(server, cause, playerId, 0));
        } else {
            // Preserve the original attempt count, but keep the newest cause/player so
            // diagnostics show the most recent lifecycle event that asked for cleanup.
            PENDING.put(dimension, new PendingCleanup(
                    server,
                    cause,
                    playerId,
                    existing.attempts()));
        }
        trace(dimension, "pending", cause, playerId,
                existing == null ? 0 : existing.attempts(), "waiting-for-post-departure-check");
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || PENDING.isEmpty()) {
            return;
        }

        for (Map.Entry<ResourceKey<Level>, PendingCleanup> entry
                : new ArrayList<>(PENDING.entrySet())) {
            process(entry.getKey(), entry.getValue());
        }
    }

    private static void process(ResourceKey<Level> dimension, PendingCleanup pending) {
        MinecraftServer server = pending.server();
        if (!PocketDimensionManager.isOwned(dimension)) {
            PENDING.remove(dimension);
            LAST_TRACE.remove(dimension);
            return;
        }

        if (PocketClaimManager.isClaimed(dimension)) {
            finishBlocked(dimension, pending, "claimed");
            return;
        }
        if (PocketClaimManager.isProtectedReturnDimension(dimension)) {
            finishBlocked(dimension, pending, "visit-return-reserved");
            return;
        }
        if (PocketDiagnostics.isReturnReserved(dimension)) {
            finishBlocked(dimension, pending, "disconnect-return-reserved");
            return;
        }
        if (PocketExpansionManager.isActiveStaging(dimension)) {
            finishBlocked(dimension, pending, "active-expansion-staging");
            return;
        }
        if (PocketDiagnostics.isInitialGeneration(dimension)) {
            finishBlocked(dimension, pending, "active-initial-generation");
            return;
        }

        ServerLevel level = server.getLevel(dimension);
        if (level == null) {
            PocketDimensionManager.teardownIfEmpty(server, dimension);
            PENDING.remove(dimension);
            if (!PocketDimensionManager.isOwned(dimension)) {
                LAST_TRACE.remove(dimension);
            } else {
                trace(dimension, "stalled", pending.cause(), pending.playerId(),
                        pending.attempts(), "level-unavailable-but-still-owned");
            }
            return;
        }

        int listedPlayers = level.players().size();
        int actualPlayers = 0;
        // The server-wide PlayerList is authoritative here. Using level.players() to
        // determine liveness would make the stale old-level list validate itself and
        // recreate the exact teardown race this retry queue is intended to absorb.
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.getLevel().dimension().equals(dimension)) {
                actualPlayers++;
            }
        }

        if (actualPlayers > 0) {
            // A real player remains. Their eventual departure will create another
            // cleanup request, so this request has done all useful work it can do.
            finishBlocked(dimension, pending, "live-players=" + actualPlayers);
            return;
        }

        if (listedPlayers > 0) {
            // The exact race this scheduler exists to absorb: the old ServerLevel still
            // lists a player whose authoritative current level has already changed.
            retryGhost(dimension, pending, "stale-player-list=" + listedPlayers);
            return;
        }

        PocketDimensionManager.teardownIfEmpty(server, dimension);
        if (!PocketDimensionManager.isOwned(dimension)) {
            PENDING.remove(dimension);
            LAST_TRACE.remove(dimension);
            BiomePockets.LOGGER.debug(
                    "Removed empty temporary pocket {} after {} cleanup request",
                    dimension.location(),
                    pending.cause());
            return;
        }

        // A protection may have appeared between the checks above and teardown.
        String blocker = PocketDiagnostics.isReturnReserved(dimension)
                ? "return-reservation-appeared"
                : "teardown-refused-empty-level";
        retryGhost(dimension, pending, blocker);
    }

    private static void retryGhost(
            ResourceKey<Level> dimension,
            PendingCleanup pending,
            String blocker) {
        int attempts = pending.attempts() + 1;
        if (attempts > MAX_GHOST_RETRIES) {
            PENDING.remove(dimension);
            trace(dimension, "stalled", pending.cause(), pending.playerId(), attempts, blocker);
            BiomePockets.LOGGER.warn(
                    "Temporary pocket {} remained after {} cleanup retries; last blocker: {}",
                    dimension.location(),
                    MAX_GHOST_RETRIES,
                    blocker);
            return;
        }

        PENDING.put(dimension, new PendingCleanup(
                pending.server(),
                pending.cause(),
                pending.playerId(),
                attempts));
        trace(dimension, "pending", pending.cause(), pending.playerId(), attempts, blocker);
    }

    private static void finishBlocked(
            ResourceKey<Level> dimension,
            PendingCleanup pending,
            String blocker) {
        PENDING.remove(dimension);
        trace(dimension, "blocked", pending.cause(), pending.playerId(), pending.attempts(), blocker);
    }

    private static void trace(
            ResourceKey<Level> dimension,
            String state,
            String cause,
            UUID playerId,
            int attempts,
            String blocker) {
        LAST_TRACE.put(dimension, new CleanupTrace(state, cause, playerId, attempts, blocker));
    }

    /** Diagnostic suffix used by /biomepockets dimensions. */
    public static String diagnostic(ResourceKey<Level> dimension) {
        CleanupTrace trace = LAST_TRACE.get(dimension);
        if (trace == null) {
            return "";
        }

        StringBuilder result = new StringBuilder();
        result.append(" [cleanup=").append(trace.state()).append(']');
        result.append(" [cleanupCause=").append(trace.cause()).append(']');
        result.append(" [cleanupAttempts=").append(trace.attempts()).append(']');
        if (trace.blocker() != null && !trace.blocker().isBlank()) {
            result.append(" [cleanupBlocker=").append(trace.blocker()).append(']');
        }
        if (trace.playerId() != null) {
            result.append(" [cleanupPlayer=").append(trace.playerId()).append(']');
        }
        return result.toString();
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        PENDING.clear();
        LAST_TRACE.clear();
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        PENDING.clear();
        LAST_TRACE.clear();
    }

    /**
     * Recovery intentionally reconstructs every marker-validated pocket first so that
     * disconnected-player reservations and permanent claims can be loaded safely. Once
     * those records are known, any remaining empty, unclaimed, unreserved pocket is a
     * stale temporary level and can resume the ordinary teardown lifecycle.
     */
    @SuppressWarnings("deprecation")
    public static void cleanupRecoveredStalePockets(MinecraftServer server) {
        Map<ResourceKey<Level>, ServerLevel> worlds = server.forgeGetWorldMap();
        int removed = 0;

        for (ResourceKey<Level> dimension : new ArrayList<>(worlds.keySet())) {
            if (!PocketDimensionManager.isOwned(dimension)
                    || PocketClaimManager.isClaimed(dimension)
                    || PocketClaimManager.isProtectedReturnDimension(dimension)
                    || PocketDiagnostics.isReturnReserved(dimension)
                    || PocketExpansionManager.isActiveStaging(dimension)
                    || PocketDiagnostics.isInitialGeneration(dimension)) {
                continue;
            }

            ServerLevel level = worlds.get(dimension);
            if (level == null || !level.players().isEmpty()) {
                continue;
            }

            PocketDimensionManager.teardownIfEmpty(server, dimension);
            if (!PocketDimensionManager.isOwned(dimension)) {
                removed++;
            }
        }

        if (removed > 0) {
            BiomePockets.LOGGER.info(
                    "Removed {} stale empty temporary BiomePockets level(s) after recovery",
                    removed);
        }
    }

    private record PendingCleanup(
            MinecraftServer server,
            String cause,
            UUID playerId,
            int attempts) { }

    private record CleanupTrace(
            String state,
            String cause,
            UUID playerId,
            int attempts,
            String blocker) { }
}
