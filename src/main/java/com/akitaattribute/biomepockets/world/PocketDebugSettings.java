package com.akitaattribute.biomepockets.world;

import com.akitaattribute.biomepockets.BiomePockets;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Runtime-only tuning knobs exposed by the selector's Debug tab.
 *
 * These deliberately reset on every server start so an aggressive test value cannot
 * silently become the permanent behavior of a world/server. The safe production
 * defaults remain the same as before the debug controls were added.
 */
@Mod.EventBusSubscriber(modid = BiomePockets.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PocketDebugSettings {
    public static final int AUTO_HEIGHT_PROBE_AXIS = 0;
    public static final int DEFAULT_GENERATION_OPS_PER_TICK = 1;
    public static final int MAX_GENERATION_OPS_PER_TICK = 16;

    private static final int[] ALLOWED_PROBE_AXES = { 0, 1, 3, 5, 7, 9 };

    private static volatile int heightProbeAxis = AUTO_HEIGHT_PROBE_AXIS;
    private static volatile int generationOpsPerTick = DEFAULT_GENERATION_OPS_PER_TICK;

    private PocketDebugSettings() { }

    public static int heightProbeAxis() {
        return heightProbeAxis;
    }

    /**
     * AUTO preserves the pre-debug behavior: 3x3 probes for a 3x3 pocket and 5x5 for
     * every larger starting pocket.
     */
    public static int effectiveHeightProbeAxis(int pocketRadius) {
        int configured = heightProbeAxis;
        if (configured == AUTO_HEIGHT_PROBE_AXIS) {
            return pocketRadius <= 1 ? 3 : 5;
        }
        return configured;
    }

    public static int generationOpsPerTick() {
        return generationOpsPerTick;
    }

    public static void update(int requestedProbeAxis, int requestedOpsPerTick) {
        heightProbeAxis = normalizeProbeAxis(requestedProbeAxis);
        generationOpsPerTick = Math.max(
                1,
                Math.min(MAX_GENERATION_OPS_PER_TICK, requestedOpsPerTick));
    }

    public static boolean canEdit(ServerPlayer player) {
        if (player == null || player.getServer() == null) {
            return false;
        }
        return player.hasPermissions(2)
                || player.getServer().isSingleplayerOwner(player.getGameProfile());
    }

    public static int normalizeProbeAxis(int requested) {
        for (int allowed : ALLOWED_PROBE_AXES) {
            if (requested == allowed) {
                return requested;
            }
        }
        return AUTO_HEIGHT_PROBE_AXIS;
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        heightProbeAxis = AUTO_HEIGHT_PROBE_AXIS;
        generationOpsPerTick = DEFAULT_GENERATION_OPS_PER_TICK;
    }
}
