package com.akitaattribute.biomepockets.world;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Dimension changes can fire while the departed ServerLevel still briefly contains
 * the moving player in its internal player list. The immediate empty-pocket teardown
 * therefore gets one delayed retry after the dimension switch has fully settled.
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

    public static void clear() {
        PENDING.clear();
    }
}
