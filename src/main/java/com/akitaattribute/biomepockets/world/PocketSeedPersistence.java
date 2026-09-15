package com.akitaattribute.biomepockets.world;

import com.akitaattribute.biomepockets.BiomePockets;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Properties;
import java.util.UUID;

/**
 * Persists the terrain-generation seed independently from the runtime ServerLevel.
 *
 * Older BiomePockets builds reconstructed dynamic ServerLevels with a new random
 * generator seed after restart while reusing the existing chunk files. That is harmless
 * until the pocket is expanded: newly generated terrain can then fail to continue the
 * terrain field that produced the original chunks. This sidecar makes the first known
 * good seed immutable and available to claims/expansion across later reconstructions.
 */
@Mod.EventBusSubscriber(modid = BiomePockets.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
final class PocketSeedPersistence {
    private static final String SEED_FILE = ".biomepockets-seed.properties";
    private static final String OWNERSHIP_MARKER = ".biomepockets-owned";
    private static final int VERSION = 1;

    private PocketSeedPersistence() { }

    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        if (PocketDimensionManager.isOwned(event.getTo())) {
            persistFromLevel(player.getLevel());
        }
    }

    /**
     * Run before the ordinary shutdown handler clears BiomePockets' runtime ownership
     * maps. Existing sidecars are immutable; this only fills in pockets that have not
     * yet had a canonical seed recorded.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onServerStopping(ServerStoppingEvent event) {
        MinecraftServer server = event.getServer();
        @SuppressWarnings("deprecation")
        Map<ResourceKey<Level>, ServerLevel> worlds = server.forgeGetWorldMap();
        for (ServerLevel level : worlds.values()) {
            if (PocketDimensionManager.isOwned(level.dimension())) {
                persistFromLevel(level);
            }
        }
    }

    static OptionalLong readSeed(MinecraftServer server, ResourceKey<Level> dimension) {
        Path folder = pocketFolder(server, dimension);
        if (!hasValidOwnershipMarker(dimension, folder)) {
            return OptionalLong.empty();
        }

        Path file = folder.resolve(SEED_FILE).normalize();
        if (!file.startsWith(folder) || !Files.isRegularFile(file)) {
            return OptionalLong.empty();
        }

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
            if (Integer.parseInt(properties.getProperty("version", "0")) != VERSION
                    || !dimension.location().toString().equals(properties.getProperty("dimension", ""))) {
                return OptionalLong.empty();
            }
            return OptionalLong.of(Long.parseLong(properties.getProperty("seed")));
        } catch (Exception exception) {
            BiomePockets.LOGGER.warn(
                    "Could not read canonical seed metadata for {}",
                    dimension.location(),
                    exception);
            return OptionalLong.empty();
        }
    }

    /**
     * Return the previously recorded canonical seed, or persist the supplied seed if
     * this pocket predates seed persistence. Existing values are deliberately never
     * overwritten: the first recorded generation seed remains the terrain authority.
     */
    static long ensureSeed(MinecraftServer server, ResourceKey<Level> dimension, long preferredSeed) {
        OptionalLong existing = readSeed(server, dimension);
        if (existing.isPresent()) {
            return existing.getAsLong();
        }

        Path folder = pocketFolder(server, dimension);
        if (!hasValidOwnershipMarker(dimension, folder)) {
            return preferredSeed;
        }
        Path file = folder.resolve(SEED_FILE).normalize();
        if (!file.startsWith(folder)) {
            return preferredSeed;
        }

        Properties properties = new Properties();
        properties.setProperty("version", Integer.toString(VERSION));
        properties.setProperty("dimension", dimension.location().toString());
        properties.setProperty("seed", Long.toString(preferredSeed));
        try (OutputStream output = Files.newOutputStream(
                file,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            properties.store(output, "BiomePockets canonical generation seed");
            BiomePockets.LOGGER.info(
                    "Persisted canonical generation seed for {}",
                    dimension.location());
            return preferredSeed;
        } catch (java.nio.file.FileAlreadyExistsException race) {
            return readSeed(server, dimension).orElse(preferredSeed);
        } catch (IOException exception) {
            BiomePockets.LOGGER.warn(
                    "Could not persist canonical seed for {}",
                    dimension.location(),
                    exception);
            return preferredSeed;
        }
    }

    private static void persistFromLevel(ServerLevel level) {
        if (!(level.getChunkSource().getGenerator() instanceof BoundedNoiseBasedChunkGenerator generator)) {
            return;
        }

        MinecraftServer server = level.getServer();
        ResourceKey<Level> dimension = level.dimension();

        // A claim seed is preferable for legacy claimed pockets because it is already
        // the seed used by the expansion pipeline. For normal/new pockets the live
        // generator seed is the original terrain seed and is recorded on first entry.
        long preferredSeed = claimedSeed(dimension).orElse(generator.getPocketSeed());
        long canonicalSeed = ensureSeed(server, dimension, preferredSeed);

        // Geometry is the first source consulted when a pocket is claimed. Keeping it
        // synchronized means an unclaimed pocket recovered with a new runtime generator
        // still claims the original persisted seed rather than the reconstruction seed.
        PocketClaimManager.writeGeometry(
                server,
                dimension,
                generator.getPocketRadius(),
                canonicalSeed);
    }

    private static OptionalLong claimedSeed(ResourceKey<Level> dimension) {
        try {
            Field claimsField = PocketClaimManager.class.getDeclaredField("CLAIMS");
            claimsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UUID, Object> claims = (Map<UUID, Object>) claimsField.get(null);
            for (Object claim : claims.values()) {
                Field dimensionField = claim.getClass().getDeclaredField("dimension");
                dimensionField.setAccessible(true);
                if (!dimension.equals(dimensionField.get(claim))) {
                    continue;
                }
                Field seedField = claim.getClass().getDeclaredField("seed");
                seedField.setAccessible(true);
                return OptionalLong.of(seedField.getLong(claim));
            }
        } catch (ReflectiveOperationException exception) {
            BiomePockets.LOGGER.debug("Could not inspect claimed pocket seed", exception);
        }
        return OptionalLong.empty();
    }

    private static Path pocketFolder(MinecraftServer server, ResourceKey<Level> dimension) {
        return DimensionType.getStorageFolder(dimension, server.getWorldPath(LevelResource.ROOT))
                .toAbsolutePath()
                .normalize();
    }

    private static boolean hasValidOwnershipMarker(ResourceKey<Level> dimension, Path folder) {
        if (!BiomePockets.MOD_ID.equals(dimension.location().getNamespace())
                || !dimension.location().getPath().startsWith("pocket_")) {
            return false;
        }

        Path root = folder.getParent();
        if (root == null
                || root.getFileName() == null
                || !BiomePockets.MOD_ID.equals(root.getFileName().toString())
                || folder.equals(root)
                || folder.getFileName() == null
                || !folder.getFileName().toString().equals(dimension.location().getPath())) {
            return false;
        }

        Path marker = folder.resolve(OWNERSHIP_MARKER).normalize();
        try {
            return marker.startsWith(folder)
                    && Files.isRegularFile(marker)
                    && Files.readString(marker, StandardCharsets.UTF_8).trim()
                    .equals(dimension.location().toString());
        } catch (IOException exception) {
            return false;
        }
    }
}
