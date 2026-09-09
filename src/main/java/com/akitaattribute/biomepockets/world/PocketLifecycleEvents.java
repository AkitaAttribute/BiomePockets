package com.akitaattribute.biomepockets.world;

import com.akitaattribute.biomepockets.BiomePockets;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

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

            // Claimed pockets are permanent. A temporary pocket saved as a Visit
            // return destination is also protected until Exit consumes that return.
            if (!PocketClaimManager.isClaimed(event.getFrom())
                    && !PocketClaimManager.isProtectedReturnDimension(event.getFrom())) {
                PocketDimensionManager.handleDeparture(player.getServer(), event.getFrom());
            }
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
        PocketPersistenceManager.recoverPockets(event.getServer());
        PocketClaimManager.load(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        PocketClaimManager.saveAll(event.getServer());

        // Do not call PocketDimensionManager.shutdown(): that method intentionally
        // force-teleports players to Overworld and deletes pocket folders. A normal
        // world/server stop now preserves them instead.
        PocketPersistenceManager.prepareForShutdown(event.getServer());
    }
}
