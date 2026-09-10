package com.akitaattribute.biomepockets.world;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Handles temporary-pocket cleanup when lifecycle timing would otherwise leave a
 * BiomePockets-owned level behind. All destructive work still routes through
 * PocketDimensionManager.teardownIfEmpty(), which retains the namespace/key/marker/
 * path safeguards used by normal pocket teardown.
 */
public final class PocketDepartureCleanup {
    private static final int RETRY_DELAY_TICKS = 2;
    private static final Map<ResourceKey<Level>, Integer> PENDING = new HashMap<>();

    /**
     * Only pockets that a player has actually entered are eligible for the continuous
     * empty-pocket sweep. This distinction is important: a newly-created pocket is
     * intentionally empty while its chunks are still being prepared asynchronously,
     * and an expansion replacement may also exist before its ownership transfer.
     */
    private static final Set<ResourceKey<Level>> ENTERED = new HashSet<>();

    private PocketDepartureCleanup() { }

    public static void markEntered(ResourceKey<Level> dimension) {
        if (PocketDimensionManager.isOwned(dimension)) {
            ENTERED.add(dimension);
        }
    }

    public static void schedule(ResourceKey<Level> dimension) {
        if (PocketDimensionManager.isOwned(dimension)) {
            ENTERED.add(dimension);
            PENDING.put(dimension, RETRY_DELAY_TICKS);
        }
    }

    public static void tick(MinecraftServer server) {
        processDelayedDepartures(server);
        sweepEnteredTemporaryPockets(server);
    }

    private static void processDelayedDepartures(MinecraftServer server) {
        Iterator<Map.Entry<ResourceKey<Level>, Integer>> iterator = PENDING.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<ResourceKey<Level>, Integer> entry = iterator.next();
            ResourceKey<Level> dimension = entry.getKey();

            if (!PocketDimensionManager.isOwned(dimension)) {
                ENTERED.remove(dimension);
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

            PocketDimensionManager.teardownIfEmpty(server, dimension);
            if (!PocketDimensionManager.isOwned(dimension)) {
                ENTERED.remove(dimension);
            }
            iterator.remove();
        }
    }

    /**
     * Do not rely exclusively on PlayerChangedDimensionEvent for cleanup. Once a
     * temporary pocket has actually been entered, keep auditing it. If it becomes
     * empty and has no permanent claim, Visit return reference, or disconnect return
     * reservation, teardownIfEmpty() is allowed to remove it. A transient veto does
     * not permanently leak the dimension; the next server tick re-evaluates it.
     */
    private static void sweepEnteredTemporaryPockets(MinecraftServer server) {
        Iterator<ResourceKey<Level>> iterator = ENTERED.iterator();
        while (iterator.hasNext()) {
            ResourceKey<Level> dimension = iterator.next();
            if (!PocketDimensionManager.isOwned(dimension)) {
                PENDING.remove(dimension);
                iterator.remove();
                continue;
            }

            if (PocketClaimManager.isClaimed(dimension)
                    || PocketClaimManager.isProtectedReturnDimension(dimension)) {
                continue;
            }

            ServerLevel level = server.getLevel(dimension);
            if (level == null) {
                PENDING.remove(dimension);
                iterator.remove();
                continue;
            }
            if (!level.players().isEmpty()) {
                continue;
            }

            PocketDimensionManager.teardownIfEmpty(server, dimension);
            if (!PocketDimensionManager.isOwned(dimension)) {
                PENDING.remove(dimension);
                iterator.remove();
            }
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
                ENTERED.remove(dimension);
                PENDING.remove(dimension);
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
        ENTERED.clear();
    }
}
