package com.akitaattribute.biomepockets.world;

import net.minecraft.server.level.ServerPlayer;

/** Operator/debug actions that bypass player-facing XP while sharing normal expansion pacing. */
public final class PocketAdminActions {
    private PocketAdminActions() { }

    public static boolean expandWithoutXp(ServerPlayer player) {
        return PocketFriendlyExpansionManager.startAdminExpansion(player);
    }
}
