package com.akitaattribute.biomepockets.world;

import com.akitaattribute.biomepockets.BiomePockets;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Operator/debug-only actions. These deliberately bypass player-facing economy rules
 * while still using the same asynchronous expansion/migration implementation.
 */
public final class PocketAdminActions {
    private PocketAdminActions() { }

    @SuppressWarnings("unchecked")
    public static boolean expandWithoutXp(ServerPlayer player) {
        UUID playerId = player.getUUID();
        try {
            Field claimsField = PocketClaimManager.class.getDeclaredField("CLAIMS");
            Field expandingField = PocketClaimManager.class.getDeclaredField("EXPANDING");
            claimsField.setAccessible(true);
            expandingField.setAccessible(true);

            Map<UUID, Object> claims = (Map<UUID, Object>) claimsField.get(null);
            Set<UUID> expanding = (Set<UUID>) expandingField.get(null);
            Object claim = claims.get(playerId);
            if (claim == null) {
                player.displayClientMessage(
                        new TextComponent("You must claim a biome pocket before expanding it."),
                        false);
                return false;
            }
            if (!expanding.add(playerId)) {
                player.displayClientMessage(
                        new TextComponent("Your biome pocket is already expanding."),
                        false);
                return false;
            }

            Class<?> claimClass = claim.getClass();
            Field dimensionField = claimClass.getDeclaredField("dimension");
            Field biomeField = claimClass.getDeclaredField("biome");
            Field seedField = claimClass.getDeclaredField("seed");
            Field radiusField = claimClass.getDeclaredField("radius");
            dimensionField.setAccessible(true);
            biomeField.setAccessible(true);
            seedField.setAccessible(true);
            radiusField.setAccessible(true);

            ResourceKey<Level> dimension = (ResourceKey<Level>) dimensionField.get(claim);
            ResourceLocation biome = (ResourceLocation) biomeField.get(claim);
            long seed = seedField.getLong(claim);
            int radius = radiusField.getInt(claim);
            int newRadius = radius + 1;
            int oldSize = radius * 2 + 1;
            int newSize = newRadius * 2 + 1;

            player.displayClientMessage(
                    new TextComponent("Preparing admin/debug biome pocket expansion "
                            + oldSize + "x" + oldSize + " -> " + newSize + "x" + newSize
                            + " (no XP cost)..."),
                    false);

            // A zero cost causes PocketClaimManager.completeExpansion to perform no
            // affordability rejection and no XP deduction, while retaining the exact
            // same generation, migration, containment, ownership and persistence path.
            PocketExpansionManager.expand(
                    player,
                    dimension,
                    biome,
                    seed,
                    radius,
                    newRadius,
                    0);
            return true;
        } catch (ReflectiveOperationException exception) {
            try {
                Field expandingField = PocketClaimManager.class.getDeclaredField("EXPANDING");
                expandingField.setAccessible(true);
                ((Set<UUID>) expandingField.get(null)).remove(playerId);
            } catch (ReflectiveOperationException ignored) {
                // Best effort only; original exception is logged below.
            }
            BiomePockets.LOGGER.error("Could not start admin/debug pocket expansion for {}", playerId, exception);
            player.displayClientMessage(
                    new TextComponent("Admin/debug pocket expansion failed. See server log."),
                    false);
            return false;
        }
    }
}
