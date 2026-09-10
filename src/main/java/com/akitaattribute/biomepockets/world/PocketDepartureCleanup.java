package com.akitaattribute.biomepockets.world;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Handles temporary-pocket cleanup when lifecycle timing would otherwise leave a
 * BiomePockets-owned level behind. All destructive work still routes through
 * PocketDimensionManager.teardownIfEmpty(), which retains the namespace/key/marker/
 * path safeguards used by normal pocket teardown.
 */
public final class PocketDepartureCleanup {
    private static final int RETRY_DELAY_TICKS = 2;
    private static final Map<ResourceKey<Level>, Integer> PENDING = new HashMap<>();

    private PocketDepartureCleanup() { }

    public static void schedule(ResourceKey<Level> dimension) {
        if (PocketDimensionManager.isOwned(dimension)) {
            PENDING.put(dimension, RETRY_DELAY_TICKS);
        }
    }

    public static void tick(MinecraftServer server) {
        Iterator<Map.Entry<ResourceKey<Level>, Integer>> iterator = PENDING.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<ResourceKey<Level>, Integer> entry = iterator.next();
            ResourceKey<Level> dimension = entry.getKey();

            if (!PocketDimensionManager.isOwned(dimension)) {
                iterator.remove();
                continue;
            }

            // A pocket may have become permanent/protected after it was scheduled.
            // Never let this retry override the claim/Visit lifetime rules.
            if (PocketClaimManager.isClaimed(dimension)
                    || PocketClaimManager.isProtectedReturnDimension(dimension)) {
                iterator.remove();
                continue;
            }

            int remaining = entry.getValue();
            if (remaining > 0) {
                entry.setValue(remaining - 1);
                continue;
            }

            ServerLevel level = server.getLevel(dimension);
            if (level != null && !level.players().isEmpty()) {
                // Another player is legitimately still in the temporary pocket. Their
                // own eventual departure will schedule another retry if needed.
                iterator.remove();
                continue;
            }

            PocketDimensionManager.teardownIfEmpty(server, dimension);
            iterator.remove();
        }
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

    public static void clear() {
        PENDING.clear();
    }
}
