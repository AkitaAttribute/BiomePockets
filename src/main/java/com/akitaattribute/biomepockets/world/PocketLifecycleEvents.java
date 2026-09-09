package com.akitaattribute.biomepockets.world;

import com.akitaattribute.biomepockets.BiomePockets;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Pocket lifetime is tied to an explicit dimension departure, not player connection
 * state. Disconnecting because of logout, a dropped connection, or a client crash
 * reserves the pocket and the exact player position for restoration on reconnect.
 *
 * Full server process persistence is still handled separately by the existing
 * startup/shutdown cleanup path; runtime pocket generators are not yet serialized as
 * persistent custom dimension definitions.
 */
@Mod.EventBusSubscriber(modid = BiomePockets.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PocketLifecycleEvents {
    private PocketLifecycleEvents() { }

    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getPlayer() instanceof ServerPlayer player) {
            PocketDimensionManager.handleDeparture(player.getServer(), event.getFrom());
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getPlayer() instanceof ServerPlayer player) {
            // This records a return reservation only. It deliberately does not trigger
            // teardown, even when the player is the only occupant of the pocket.
            PocketDimensionManager.rememberDisconnect(player);
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getPlayer() instanceof ServerPlayer player) {
            PocketDimensionManager.restoreAfterLogin(player);
        }
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        PocketDimensionManager.cleanupStalePockets(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        PocketDimensionManager.shutdown(event.getServer());
    }
}
