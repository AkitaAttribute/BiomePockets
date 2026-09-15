package com.akitaattribute.biomepockets.world;

import com.akitaattribute.biomepockets.BiomePockets;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Runtime-only tuning knobs exposed by the selector's Debug tab.
 *
 * The names heightProbeAxis/generationOpsPerTick are retained as wire-compatible aliases
 * for the build-143 packet shape. Their values now mean an exact number of chunk-center
 * height probes and a percentage of a pocket's total generation-work counter.
 */
@Mod.EventBusSubscriber(modid = BiomePockets.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PocketDebugSettings {
    public static final int DEFAULT_HEIGHT_PROBES = 1;
    public static final int DEFAULT_GENERATION_BATCH_PERCENT = 1;

    // Kept for the existing packet handler. It now represents the maximum percentage.
    public static final int MAX_GENERATION_OPS_PER_TICK = 100;

    private static final int[] ALLOWED_HEIGHT_PROBES = { 1, 5, 9 };
    private static final int[] ALLOWED_BATCH_PERCENTS = { 1, 2, 5, 10, 20, 25, 50, 100 };

    private static volatile int heightProbes = DEFAULT_HEIGHT_PROBES;
    private static volatile int generationBatchPercent = DEFAULT_GENERATION_BATCH_PERCENT;

    private PocketDebugSettings() { }

    public static int heightProbes() {
        return heightProbes;
    }

    public static int generationBatchPercent() {
        return generationBatchPercent;
    }

    /**
     * Wire-compatible alias used by the current selector-open packet.
     */
    public static int heightProbeAxis() {
        return heightProbes;
    }

    /**
     * Wire-compatible alias used by the current selector-open packet.
     */
    public static int generationOpsPerTick() {
        return generationBatchPercent;
    }

    public static int generationBatchSize(int totalOperations) {
        int total = Math.max(1, totalOperations);
        return Math.max(1, (total * generationBatchPercent + 99) / 100);
    }

    public static void update(int requestedHeightProbes, int requestedBatchPercent) {
        heightProbes = normalizeProbeAxis(requestedHeightProbes);
        generationBatchPercent = normalizeBatchPercent(requestedBatchPercent);
    }

    public static boolean canEdit(ServerPlayer player) {
        if (player == null || player.getServer() == null) {
            return false;
        }
        return player.hasPermissions(2)
                || player.getServer().isSingleplayerOwner(player.getGameProfile());
    }

    /**
     * Retained under the old method name so the existing debug packet does not need a
     * protocol-shape change. The input is now a probe COUNT, not a grid axis length.
     */
    public static int normalizeProbeAxis(int requested) {
        for (int allowed : ALLOWED_HEIGHT_PROBES) {
            if (requested == allowed) {
                return requested;
            }
        }
        return DEFAULT_HEIGHT_PROBES;
    }

    public static int normalizeBatchPercent(int requested) {
        for (int allowed : ALLOWED_BATCH_PERCENTS) {
            if (requested == allowed) {
                return requested;
            }
        }

        // Packet values from an older build may not be one of the new steps. Snap to
        // the nearest valid percentage rather than silently converting 16 to 100.
        int nearest = ALLOWED_BATCH_PERCENTS[0];
        int nearestDistance = Math.abs(requested - nearest);
        for (int allowed : ALLOWED_BATCH_PERCENTS) {
            int distance = Math.abs(requested - allowed);
            if (distance < nearestDistance) {
                nearest = allowed;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        heightProbes = DEFAULT_HEIGHT_PROBES;
        generationBatchPercent = DEFAULT_GENERATION_BATCH_PERCENT;
    }
}
