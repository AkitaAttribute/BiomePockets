package com.akitaattribute.biomepockets.world;

import com.akitaattribute.biomepockets.BiomePockets;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;

/** Read-only diagnostic helpers for /biomepockets dimensions and cleanup. */
public final class PocketDiagnostics {
    private PocketDiagnostics() { }

    public static String describe(MinecraftServer server, ResourceKey<Level> dimension) {
        if (!PocketDimensionManager.isOwned(dimension)) {
            return "";
        }

        ResourceLocation biome = ownedBiome(dimension);
        UUID owner = claimOwner(dimension);
        boolean returnReserved = isReturnReserved(dimension);
        boolean activeStaging = PocketExpansionManager.isActiveStaging(dimension);
        boolean initialGeneration = isInitialGeneration(dimension);

        StringBuilder result = new StringBuilder(" [BiomePockets-owned]");
        if (owner != null) {
            result.append(" [type=claimed]");
            ServerPlayer onlineOwner = server.getPlayerList().getPlayer(owner);
            result.append(" [owner=")
                    .append(onlineOwner != null ? onlineOwner.getGameProfile().getName() : owner)
                    .append(']');
        } else if (activeStaging) {
            result.append(" [type=expansion-staging]");
        } else if (initialGeneration) {
            result.append(" [type=initial-generation]");
        } else if (returnReserved) {
            result.append(" [type=return-reserved]");
        } else {
            result.append(" [type=temporary]");
        }

        result.append(" [biome=")
                .append(biome == null ? "unknown" : biome)
                .append(']');

        String cleanupState = PocketDepartureCleanup.diagnostic(dimension);
        if (!cleanupState.isEmpty()) {
            result.append(cleanupState);
        } else if (owner == null && !activeStaging && !initialGeneration && !returnReserved) {
            ServerLevel level = server.getLevel(dimension);
            if (level != null && level.players().isEmpty()) {
                // This is the most useful failure signal for the lifecycle bug: an
                // empty temporary pocket exists, but no departure cleanup request is
                // pending and no retained cleanup trace explains why it was kept.
                result.append(" [cleanup=untracked-empty]");
            }
        }
        return result.toString();
    }

    public static boolean isReturnReserved(ResourceKey<Level> dimension) {
        return PocketClaimManager.isProtectedReturnDimension(dimension)
                || hasDisconnectReservation(dimension)
                || hasPersistedReturnReservation(dimension);
    }

    /**
     * Initial generation worlds are empty until the creating player is teleported into
     * them. Treat them as active work rather than orphaned temporary dimensions.
     */
    @SuppressWarnings("unchecked")
    public static boolean isInitialGeneration(ResourceKey<Level> dimension) {
        try {
            Field jobsField = PocketThrottledInitialGenerator.class.getDeclaredField("JOBS_BY_PLAYER");
            jobsField.setAccessible(true);
            Map<UUID, Object> jobs = (Map<UUID, Object>) jobsField.get(null);
            for (Object job : jobs.values()) {
                Field levelKeyField = job.getClass().getDeclaredField("levelKey");
                levelKeyField.setAccessible(true);
                Object value = levelKeyField.get(job);
                if (dimension.equals(value)) {
                    return true;
                }
            }
        } catch (ReflectiveOperationException exception) {
            BiomePockets.LOGGER.debug("Could not inspect active initial pocket generation", exception);
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static ResourceLocation ownedBiome(ResourceKey<Level> dimension) {
        try {
            Field ownedField = PocketDimensionManager.class.getDeclaredField("OWNED");
            ownedField.setAccessible(true);
            Map<ResourceKey<Level>, Object> owned = (Map<ResourceKey<Level>, Object>) ownedField.get(null);
            Object record = owned.get(dimension);
            if (record == null) {
                return null;
            }
            Method biomeMethod = record.getClass().getDeclaredMethod("biome");
            biomeMethod.setAccessible(true);
            return (ResourceLocation) biomeMethod.invoke(record);
        } catch (ReflectiveOperationException exception) {
            BiomePockets.LOGGER.debug("Could not read pocket biome diagnostic for {}", dimension.location(), exception);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static UUID claimOwner(ResourceKey<Level> dimension) {
        try {
            Field ownersField = PocketClaimManager.class.getDeclaredField("OWNERS");
            ownersField.setAccessible(true);
            Map<ResourceKey<Level>, UUID> owners =
                    (Map<ResourceKey<Level>, UUID>) ownersField.get(null);
            return owners.get(dimension);
        } catch (ReflectiveOperationException exception) {
            BiomePockets.LOGGER.debug("Could not read pocket claim owner diagnostic for {}", dimension.location(), exception);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean hasDisconnectReservation(ResourceKey<Level> dimension) {
        try {
            Field reservationsField = PocketDimensionManager.class.getDeclaredField("DISCONNECTED_PLAYERS");
            reservationsField.setAccessible(true);
            Map<UUID, Object> reservations = (Map<UUID, Object>) reservationsField.get(null);
            for (Object reservation : reservations.values()) {
                Method dimensionMethod = reservation.getClass().getDeclaredMethod("dimension");
                dimensionMethod.setAccessible(true);
                if (dimension.equals(dimensionMethod.invoke(reservation))) {
                    return true;
                }
            }
        } catch (ReflectiveOperationException exception) {
            BiomePockets.LOGGER.debug("Could not read disconnect reservations for diagnostics", exception);
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static boolean hasPersistedReturnReservation(ResourceKey<Level> dimension) {
        try {
            Field reservationsField = PocketPersistenceManager.class.getDeclaredField("PERSISTED_RETURNS");
            reservationsField.setAccessible(true);
            Map<UUID, Object> reservations = (Map<UUID, Object>) reservationsField.get(null);
            for (Object reservation : reservations.values()) {
                Method dimensionMethod = reservation.getClass().getDeclaredMethod("dimension");
                dimensionMethod.setAccessible(true);
                if (dimension.equals(dimensionMethod.invoke(reservation))) {
                    return true;
                }
            }
        } catch (ReflectiveOperationException exception) {
            BiomePockets.LOGGER.debug("Could not read persisted return reservations for diagnostics", exception);
        }
        return false;
    }
}
