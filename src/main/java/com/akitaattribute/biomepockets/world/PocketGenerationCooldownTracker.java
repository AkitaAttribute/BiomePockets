package com.akitaattribute.biomepockets.world;

import com.akitaattribute.biomepockets.BiomePockets;
import com.akitaattribute.biomepockets.network.NetworkHandler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Bridges the throttled generator's real completed/total work count into Minecraft's
 * item cooldown system. The server cooldown blocks reuse; a small progress packet keeps
 * the client's vanilla cooldown overlay pinned to actual generation progress.
 */
@Mod.EventBusSubscriber(modid = BiomePockets.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PocketGenerationCooldownTracker {
    private static final int SERVER_COOLDOWN_TICKS = 1_000_000;
    private static final Map<UUID, TrackedCooldown> TRACKED = new HashMap<>();

    private static Field jobsByPlayerField;
    private static Field completedOperationsField;
    private static Method totalOperationsMethod;

    private PocketGenerationCooldownTracker() { }

    /**
     * Called immediately after createAndTeleport. It only starts a cooldown if the
     * generator actually queued a job for this player, so failed or duplicate requests
     * cannot create a phantom cooldown.
     */
    public static void begin(ServerPlayer player, Item sourceItem) {
        if (TRACKED.containsKey(player.getUUID())) {
            return;
        }

        Progress progress = readProgress(player.getUUID());
        if (progress == null) {
            return;
        }

        player.getCooldowns().addCooldown(sourceItem, SERVER_COOLDOWN_TICKS);
        TrackedCooldown tracked = new TrackedCooldown(sourceItem, -1, -1);
        TRACKED.put(player.getUUID(), tracked);
        sync(player, tracked, progress);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || TRACKED.isEmpty()) {
            return;
        }

        MinecraftServer server = event.getServer();
        for (UUID playerId : new ArrayList<>(TRACKED.keySet())) {
            TrackedCooldown tracked = TRACKED.get(playerId);
            if (tracked == null) {
                continue;
            }

            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) {
                TRACKED.remove(playerId);
                continue;
            }

            Progress progress = readProgress(playerId);
            if (progress == null) {
                clear(player, tracked.item());
                TRACKED.remove(playerId);
                continue;
            }

            if (progress.completed() != tracked.lastCompleted()
                    || progress.total() != tracked.lastTotal()) {
                sync(player, tracked, progress);
                TRACKED.put(playerId, new TrackedCooldown(
                        tracked.item(),
                        progress.completed(),
                        progress.total()));
            }
        }
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        resetRuntimeState();
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        resetRuntimeState();
    }

    private static void sync(ServerPlayer player, TrackedCooldown tracked, Progress progress) {
        NetworkHandler.sendGenerationCooldown(
                player,
                tracked.item(),
                progress.completed(),
                progress.total(),
                true);
    }

    private static void clear(ServerPlayer player, Item item) {
        player.getCooldowns().removeCooldown(item);
        NetworkHandler.sendGenerationCooldown(player, item, 0, 0, false);
    }

    @SuppressWarnings("unchecked")
    private static Progress readProgress(UUID playerId) {
        try {
            if (jobsByPlayerField == null) {
                jobsByPlayerField = PocketThrottledInitialGenerator.class.getDeclaredField("JOBS_BY_PLAYER");
                jobsByPlayerField.setAccessible(true);
            }

            Map<UUID, Object> jobs = (Map<UUID, Object>) jobsByPlayerField.get(null);
            Object job = jobs.get(playerId);
            if (job == null) {
                return null;
            }

            if (completedOperationsField == null || completedOperationsField.getDeclaringClass() != job.getClass()) {
                completedOperationsField = job.getClass().getDeclaredField("completedOperations");
                completedOperationsField.setAccessible(true);
                totalOperationsMethod = job.getClass().getDeclaredMethod("totalOperations");
                totalOperationsMethod.setAccessible(true);
            }

            int completed = completedOperationsField.getInt(job);
            int total = (Integer) totalOperationsMethod.invoke(job);
            return new Progress(Math.max(0, completed), Math.max(1, total));
        } catch (ReflectiveOperationException exception) {
            BiomePockets.LOGGER.error("Could not read throttled pocket generation progress", exception);
            return null;
        }
    }

    private static void resetRuntimeState() {
        TRACKED.clear();
        jobsByPlayerField = null;
        completedOperationsField = null;
        totalOperationsMethod = null;
    }

    private record TrackedCooldown(Item item, int lastCompleted, int lastTotal) { }

    private record Progress(int completed, int total) { }
}
