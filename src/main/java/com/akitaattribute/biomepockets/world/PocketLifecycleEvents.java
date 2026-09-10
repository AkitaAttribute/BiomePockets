package com.akitaattribute.biomepockets.world;

import com.akitaattribute.biomepockets.BiomePockets;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.event.world.SleepFinishedTimeEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

/**
 * Pocket lifetime is tied to an explicit dimension departure, not connection or
 * process lifetime. Disconnects preserve exact return coordinates, while orderly
 * server/world shutdown preserves the pocket folder and reconstructs its bounded
 * runtime generator on the next start.
 */
@Mod.EventBusSubscriber(modid = BiomePockets.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PocketLifecycleEvents {
    private PocketLifecycleEvents() { }

    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getPlayer() instanceof ServerPlayer player) {
            PocketClaimManager.handleDimensionChange(player, event.getFrom(), event.getTo());
            PocketPersistenceManager.handleDimensionChange(player, event.getFrom(), event.getTo());

            // Mark actual arrivals, not merely created dimensions. This lets the
            // periodic cleanup distinguish a pocket that has been used and later
            // abandoned from one that is still being prepared asynchronously.
            PocketDepartureCleanup.markEntered(event.getTo());

            // Claimed pockets are permanent. A temporary pocket saved as a Visit
            // return destination is also protected until Exit consumes that return.
            if (!PocketClaimManager.isClaimed(event.getFrom())
                    && !PocketClaimManager.isProtectedReturnDimension(event.getFrom())) {
                PocketDimensionManager.handleDeparture(player.getServer(), event.getFrom());

                // Retain the short delayed retry, but it is no longer our only cleanup
                // mechanism. The regular sweep will continue auditing an entered
                // temporary pocket until teardown actually succeeds or it becomes
                // legitimately protected.
                PocketDepartureCleanup.schedule(event.getFrom());
            }
        }
    }

    @SubscribeEvent
    public static void onSleepFinished(SleepFinishedTimeEvent event) {
        if (!(event.getWorld() instanceof ServerLevel sleepingLevel)
                || !PocketDimensionManager.isOwned(sleepingLevel.dimension())) {
            return;
        }

        ServerLevel overworld = sleepingLevel.getServer().getLevel(Level.OVERWORLD);
        if (overworld == null) {
            return;
        }

        /*
         * Dynamic non-overworld levels use DerivedLevelData. In 1.18.2 that data can
         * read the shared day time, but its setDayTime implementation does not update
         * the primary world clock. Vanilla/Forge therefore computes the correct wake
         * time and wakes the sleepers, but the clock itself does not move.
         *
         * SleepFinishedTimeEvent is fired immediately before ServerLevel#setDayTime.
         * Write the same requested wake time into the Overworld's primary level data;
         * every pocket then observes that shared time through DerivedLevelData.
         */
        long wakeTime = event.getNewTime();
        overworld.setDayTime(wakeTime);
        BiomePockets.LOGGER.debug(
                "Advanced shared day time to {} after sleeping in pocket {}",
                wakeTime,
                sleepingLevel.dimension().location());
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        // END fires after Minecraft has finished iterating/ticking its ServerLevels.
        // Removing a dynamic ServerLevel here is safer than mutating the world map from
        // inside a WorldTickEvent while that map may still be actively iterated.
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            PocketDepartureCleanup.tick(server);
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getPlayer() instanceof ServerPlayer player) {
            // If logout occurs inside the player's permanent pocket, preserve that
            // exact location as the next Visit destination.
            PocketClaimManager.rememberCurrentPocketPosition(player);
            PocketClaimManager.saveAll(player.getServer());

            // Both reservations are non-destructive. The in-memory copy handles a
            // normal reconnect; the disk-backed copy survives integrated/dedicated
            // server shutdown and process restart.
            PocketDimensionManager.rememberDisconnect(player);
            PocketPersistenceManager.rememberDisconnect(player);
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getPlayer() instanceof ServerPlayer player) {
            PocketDimensionManager.restoreAfterLogin(player);
            PocketPersistenceManager.restoreAfterLogin(player);
        }
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        PocketDepartureCleanup.clear();
        PocketPersistenceManager.recoverPockets(event.getServer());
        PocketClaimManager.load(event.getServer());

        // Recovery must happen before this cleanup so claim files, Visit return points,
        // and disconnect reservations are all known. Anything still empty/unclaimed/
        // unreserved after that is a stale temporary pocket and can safely be removed
        // through the ordinary marker-validated teardown path.
        PocketDepartureCleanup.cleanupRecoveredStalePockets(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        PocketDepartureCleanup.clear();
        PocketClaimManager.saveAll(event.getServer());

        // Do not call PocketDimensionManager.shutdown(): that method intentionally
        // force-teleports players to Overworld and deletes pocket folders. A normal
        // world/server stop now preserves them instead.
        PocketPersistenceManager.prepareForShutdown(event.getServer());
    }
}
