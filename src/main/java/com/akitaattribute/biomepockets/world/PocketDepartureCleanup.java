package com.akitaattribute.biomepockets.world;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Map;

/**
 * Startup-only cleanup for stale temporary pockets left behind by an interrupted
 * shutdown or by older BiomePockets builds. Runtime teardown is event-driven from
 * actual dimension departures; there is deliberately no per-tick pocket polling.
 *
 * All destructive work still routes through PocketDimensionManager.teardownIfEmpty(),
 * which retains the namespace/key/marker/path safeguards used by normal teardown.
 */
public final class PocketDepartureCleanup {
    private PocketDepartureCleanup() { }

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
                    || PocketClaimManager.isProtectedReturnDimension(dimension)) {
                continue;
            }

            ServerLevel level = worlds.get(dimension);
            if (level == null || !level.players().isEmpty()) {
                continue;
            }

            // teardownIfEmpty also checks disconnect/persisted return reservations.
            // Therefore a player who logged out inside an unclaimed temporary pocket
            // is still preserved for reconnect and will not be deleted here.
            PocketDimensionManager.teardownIfEmpty(server, dimension);
            if (!PocketDimensionManager.isOwned(dimension)) {
                removed++;
            }
        }

        if (removed > 0) {
            com.akitaattribute.biomepockets.BiomePockets.LOGGER.info(
                    "Removed {} stale empty temporary BiomePockets level(s) after recovery",
                    removed);
        }
    }
}
