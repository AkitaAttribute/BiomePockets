package com.akitaattribute.biomepockets.world;

import com.akitaattribute.biomepockets.BiomePockets;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Pocket lifetime is tied to actual player dimension departures rather than polling.
 * Ordinary travel is handled by PlayerChangedDimensionEvent. Death/respawn is a
 * separate Forge path in 1.18.2, so Clone records the pre-respawn dimension and
 * PlayerRespawnEvent compares it with the player's actual respawn dimension.
 *
 * Disconnects preserve exact return coordinates, while orderly server/world shutdown
 * preserves the pocket folder and reconstructs its bounded runtime generator on the
 * next start.
 */
@Mod.EventBusSubscriber(modid = BiomePockets.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PocketLifecycleEvents {
    private static final Map<UUID, ResourceKey<Level>> RESPAWN_ORIGINS = new HashMap<>();

    private PocketLifecycleEvents() { }

    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getPlayer() instanceof ServerPlayer player) {
            handleActualDimensionDeparture(player, event.getFrom(), event.getTo());
        }
    }

    /**
     * Forge clones ServerPlayer for death respawns (and certain End-return flows).
     * Capture the original dimension here; the new player's final respawn dimension is
     * not authoritative until PlayerRespawnEvent fires later in PlayerList.
     */
    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event) {
        Player original = event.getOriginal();
        if (!(event.getPlayer() instanceof ServerPlayer newPlayer)) {
            return;
        }

        ResourceKey<Level> from = original.getLevel().dimension();
        if (PocketDimensionManager.isOwned(from)) {
            RESPAWN_ORIGINS.put(newPlayer.getUUID(), from);
        } else {
            RESPAWN_ORIGINS.remove(newPlayer.getUUID());
        }
    }

    /**
     * Death alone is not a teardown condition. Only a respawn into a different
     * dimension counts as leaving the pocket. Respawning inside the same pocket keeps
     * it alive exactly as ordinary same-dimension gameplay would.
     */
    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }

        ResourceKey<Level> from = RESPAWN_ORIGINS.remove(player.getUUID());
        if (from == null) {
            return;
        }

        ResourceKey<Level> to = player.getLevel().dimension();
        if (!from.equals(to)) {
            BiomePockets.LOGGER.debug(
                    "Player {} respawned from pocket {} into {}; processing dimension departure",
                    player.getGameProfile().getName(),
                    from.location(),
                    to.location());
            handleActualDimensionDeparture(player, from, to);
        }
    }

    private static void handleActualDimensionDeparture(
            ServerPlayer player,
            ResourceKey<Level> from,
            ResourceKey<Level> to) {
        if (from.equals(to)) {
            return;
        }

        PocketClaimManager.handleDimensionChange(player, from, to);
        PocketPersistenceManager.handleDimensionChange(player, from, to);

        if (!PocketClaimManager.isClaimed(from)
                && !PocketClaimManager.isProtectedReturnDimension(from)) {
            PocketDimensionManager.handleDeparture(player.getServer(), from);
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getPlayer() instanceof ServerPlayer player) {
            RESPAWN_ORIGINS.remove(player.getUUID());

            PocketClaimManager.rememberCurrentPocketPosition(player);
            PocketClaimManager.saveAll(player.getServer());

            PocketDimensionManager.rememberDisconnect(player);
            PocketPersistenceManager.rememberDisconnect(player);
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getPlayer() instanceof ServerPlayer player) {
            RESPAWN_ORIGINS.remove(player.getUUID());
            PocketDimensionManager.restoreAfterLogin(player);
            PocketPersistenceManager.restoreAfterLogin(player);
        }
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        RESPAWN_ORIGINS.clear();
        PocketPersistenceManager.recoverPockets(event.getServer());
        PocketClaimManager.load(event.getServer());

        // Persistence reconstructs dynamic levels before the private claim records are
        // loaded. Restore each claimed generator's logical radius from that claim so an
        // expanded 5x5/7x7 pocket keeps the same bounds after a server restart.
        PocketClaimExpansionBridge.syncLoadedClaimGeometry(event.getServer());

        // This is not runtime polling. It is a one-time recovery cleanup for stale
        // temporary pockets left by older builds or an interrupted previous session,
        // after claims and reconnect reservations have been reconstructed.
        PocketDepartureCleanup.cleanupRecoveredStalePockets(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        RESPAWN_ORIGINS.clear();
        PocketClaimManager.saveAll(event.getServer());

        // Do not call PocketDimensionManager.shutdown(): that method intentionally
        // force-teleports players to Overworld and deletes pocket folders. A normal
        // world/server stop now preserves them instead.
        PocketPersistenceManager.prepareForShutdown(event.getServer());
    }
}
