package com.akitaattribute.biomepockets.world;

import com.akitaattribute.biomepockets.BiomePockets;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Pocket lifetime is tied to an explicit dimension departure, not connection state.
 *
 * Disconnecting (including a dropped connection or client crash) must leave the
 * pocket intact so vanilla player data can restore the player to the same dimension
 * and coordinates on reconnect. Likewise, server shutdown/startup must not be treated
 * as every player leaving their pocket.
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
}
