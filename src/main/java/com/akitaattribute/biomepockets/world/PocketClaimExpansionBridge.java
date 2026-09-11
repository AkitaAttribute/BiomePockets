package com.akitaattribute.biomepockets.world;

import com.akitaattribute.biomepockets.BiomePockets;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Small bridge for the private permanent-claim state while expansion is migrated to
 * an in-place world update. The claimed dimension key is deliberately never changed.
 */
final class PocketClaimExpansionBridge {
    private PocketClaimExpansionBridge() { }

    static boolean canCommit(
            MinecraftServer server,
            UUID playerId,
            ResourceKey<Level> claimedDimension,
            int cost) {
        try {
            State state = state(playerId);
            if (state.claim == null || !state.expanding.contains(playerId)) {
                return false;
            }
            ResourceKey<Level> actualDimension = dimension(state.claim);
            if (!claimedDimension.equals(actualDimension)) {
                return false;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            return player != null
                    && (cost <= 0 || player.getAbilities().instabuild || player.totalExperience >= cost);
        } catch (ReflectiveOperationException exception) {
            BiomePockets.LOGGER.error("Could not validate in-place pocket expansion", exception);
            return false;
        }
    }

    static boolean commit(
            MinecraftServer server,
            UUID playerId,
            ResourceKey<Level> claimedDimension,
            int newRadius,
            long seed,
            int cost) {
        try {
            State state = state(playerId);
            Object claim = state.claim;
            if (claim == null || !state.expanding.contains(playerId)
                    || !claimedDimension.equals(dimension(claim))) {
                return false;
            }

            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) {
                state.expanding.remove(playerId);
                return false;
            }
            if (cost > 0 && !player.getAbilities().instabuild && player.totalExperience < cost) {
                state.expanding.remove(playerId);
                player.displayClientMessage(
                        new TextComponent("Expansion cancelled because you no longer have the required experience."),
                        false);
                return false;
            }

            Field radiusField = claim.getClass().getDeclaredField("radius");
            Field expansionsField = claim.getClass().getDeclaredField("expansions");
            radiusField.setAccessible(true);
            expansionsField.setAccessible(true);
            radiusField.setInt(claim, newRadius);
            expansionsField.setInt(claim, expansionsField.getInt(claim) + 1);

            if (cost > 0 && !player.getAbilities().instabuild) {
                player.giveExperiencePoints(-cost);
            }

            long canonicalSeed = PocketSeedPersistence.ensureSeed(server, claimedDimension, seed);
            PocketClaimManager.writeGeometry(server, claimedDimension, newRadius, canonicalSeed);
            saveClaim(server, claim);
            state.expanding.remove(playerId);

            int size = newRadius * 2 + 1;
            player.displayClientMessage(
                    new TextComponent("Expanded your claimed biome pocket to " + size + "x" + size
                            + " chunks" + (cost > 0 ? " for " + cost + " experience points." : ".")),
                    false);
            return true;
        } catch (ReflectiveOperationException exception) {
            BiomePockets.LOGGER.error("Could not commit in-place pocket expansion", exception);
            PocketClaimManager.expansionFailed(server, playerId, "Biome pocket expansion failed while saving claim state.");
            return false;
        }
    }

    static void syncLoadedClaimGeometry(MinecraftServer server) {
        try {
            Field claimsField = PocketClaimManager.class.getDeclaredField("CLAIMS");
            claimsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UUID, Object> claims = (Map<UUID, Object>) claimsField.get(null);

            for (Object claim : claims.values()) {
                ResourceKey<Level> dimension = dimension(claim);
                Field radiusField = claim.getClass().getDeclaredField("radius");
                Field seedField = claim.getClass().getDeclaredField("seed");
                radiusField.setAccessible(true);
                seedField.setAccessible(true);
                int radius = Math.max(1, radiusField.getInt(claim));
                long claimSeed = seedField.getLong(claim);
                long canonicalSeed = PocketSeedPersistence.ensureSeed(server, dimension, claimSeed);

                // Legacy recovery could leave a claim pointing at a reconstructed
                // runtime seed. Once a canonical sidecar exists, make the claim use
                // that same seed for all future staging generation and persist the fix.
                if (canonicalSeed != claimSeed) {
                    seedField.setLong(claim, canonicalSeed);
                    saveClaim(server, claim);
                    BiomePockets.LOGGER.info(
                            "Reconciled claimed pocket {} to its canonical generation seed",
                            dimension.location());
                }
                PocketClaimManager.writeGeometry(server, dimension, radius, canonicalSeed);

                ServerLevel level = server.getLevel(dimension);
                if (level == null) {
                    continue;
                }
                if (level.getChunkSource().getGenerator() instanceof BoundedNoiseBasedChunkGenerator generator) {
                    generator.resizePocket(radius);
                }
            }
        } catch (ReflectiveOperationException exception) {
            BiomePockets.LOGGER.error("Could not synchronize recovered claimed-pocket geometry", exception);
        }
    }

    private static State state(UUID playerId) throws ReflectiveOperationException {
        Field claimsField = PocketClaimManager.class.getDeclaredField("CLAIMS");
        Field expandingField = PocketClaimManager.class.getDeclaredField("EXPANDING");
        claimsField.setAccessible(true);
        expandingField.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<UUID, Object> claims = (Map<UUID, Object>) claimsField.get(null);
        @SuppressWarnings("unchecked")
        Set<UUID> expanding = (Set<UUID>) expandingField.get(null);
        return new State(claims.get(playerId), expanding);
    }

    @SuppressWarnings("unchecked")
    private static ResourceKey<Level> dimension(Object claim) throws ReflectiveOperationException {
        Field dimensionField = claim.getClass().getDeclaredField("dimension");
        dimensionField.setAccessible(true);
        return (ResourceKey<Level>) dimensionField.get(claim);
    }

    private static void saveClaim(MinecraftServer server, Object claim) throws ReflectiveOperationException {
        Method saveMethod = PocketClaimManager.class.getDeclaredMethod(
                "saveClaim",
                MinecraftServer.class,
                claim.getClass());
        saveMethod.setAccessible(true);
        saveMethod.invoke(null, server, claim);
    }

    private record State(Object claim, Set<UUID> expanding) { }
}
