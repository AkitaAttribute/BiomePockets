package com.akitaattribute.biomepockets.world;

import com.akitaattribute.biomepockets.BiomePockets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.common.BiomeDictionary;
import net.minecraftforge.common.Tags;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Corrects the initial arrival point for a newly-created pocket and disables the
 * vanilla End boss controller in End-profile pockets. Disconnect/reconnect restores
 * are intentionally not repositioned: only the first dimension entry receives the
 * initial spawn policy.
 */
@Mod.EventBusSubscriber(modid = BiomePockets.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PocketWorldSafetyEvents {
    private static final Set<ResourceKey<Level>> INITIAL_SPAWN_APPLIED = new HashSet<>();

    private PocketWorldSafetyEvents() { }

    @SubscribeEvent
    public static void onWorldLoad(WorldEvent.Load event) {
        if (!(event.getWorld() instanceof ServerLevel level) || !isPocketLevel(level.dimension())) {
            return;
        }

        // The built-in End DimensionType has createDragonFight=true. We reuse its
        // visual/environmental behavior for End pockets, but pockets are not the
        // canonical vanilla End and must never own an EndDragonFight.
        if (level.dimensionType().createDragonFight() && level.dragonFight != null) {
            level.dragonFight = null;
            BiomePockets.LOGGER.info(
                    "Disabled vanilla End dragon fight controller for pocket {}",
                    level.dimension().location());
        }
    }

    @SubscribeEvent
    public static void onWorldUnload(WorldEvent.Unload event) {
        if (event.getWorld() instanceof ServerLevel level && isPocketLevel(level.dimension())) {
            INITIAL_SPAWN_APPLIED.remove(level.dimension());
        }
    }

    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }

        ResourceKey<Level> destination = event.getTo();
        if (!isPocketLevel(destination) || !INITIAL_SPAWN_APPLIED.add(destination)) {
            return;
        }

        // Forge fires PlayerChangedDimensionEvent after the player has already been
        // installed in the destination ServerLevel. Reposition immediately in this
        // event rather than scheduling another tick.
        applyInitialSpawn(player.getServer(), player.getUUID(), destination);
    }

    private static void applyInitialSpawn(
            MinecraftServer server,
            java.util.UUID playerId,
            ResourceKey<Level> destination) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        ServerLevel level = server.getLevel(destination);
        if (player == null || level == null || !player.getLevel().dimension().equals(destination)) {
            INITIAL_SPAWN_APPLIED.remove(destination);
            return;
        }

        int biomeProbeY = level.getMinBuildHeight()
                + Math.max(1, (level.getMaxBuildHeight() - level.getMinBuildHeight()) / 2);
        Holder<Biome> biome = level.getBiome(new BlockPos(0, biomeProbeY, 0));
        boolean nether = level.dimensionTypeRegistration().is(DimensionType.NETHER_LOCATION);
        boolean end = level.dimensionTypeRegistration().is(DimensionType.END_LOCATION);
        boolean underground = isUndergroundBiome(biome);

        BlockPos spawn;
        if (nether) {
            spawn = findHighestNetherOpening(level);
            if (spawn == null) {
                spawn = carveProtectedNetherChamber(level);
            }
        } else if (underground) {
            // Cave biomes are the only non-Nether profile where an underground
            // opening is intentionally preferred.
            spawn = findCaveOpeningNearCenter(level);
            if (spawn == null) {
                spawn = findHighestCenterGround(level);
            }
            if (spawn == null) {
                spawn = buildCenterPlatform(level, end ? Blocks.END_STONE.defaultBlockState() : Blocks.STONE.defaultBlockState());
            }
        } else {
            // Normal Overworld/End-style biomes always use the actual top of the
            // center column. Water/lava at that surface is replaced in-place by a
            // platform instead of treating the column as missing and falling back to
            // an arbitrary midpoint Y.
            spawn = findHighestCenterGround(level);
            if (spawn == null) {
                spawn = buildCenterPlatform(level, end ? Blocks.END_STONE.defaultBlockState() : Blocks.STONE.defaultBlockState());
            }
        }

        player.teleportTo(
                level,
                spawn.getX() + 0.5D,
                spawn.getY(),
                spawn.getZ() + 0.5D,
                player.getYRot(),
                player.getXRot());

        BiomePockets.LOGGER.info(
                "Applied initial pocket spawn for {} at {} {} {} (nether={}, end={}, underground={})",
                destination.location(),
                spawn.getX(),
                spawn.getY(),
                spawn.getZ(),
                nether,
                end,
                underground);
    }

    /**
     * Normal Overworld/End policy: use the top of the exact center column, including
     * the natural fluid surface. MOTION_BLOCKING_NO_LEAVES deliberately ignores tree
     * leaves while still treating water/lava as the surface. If that top surface is
     * not standable, replace the surface itself with a 3x3 platform. Only a genuinely
     * empty/void column returns null and reaches the arbitrary-height void fallback.
     */
    private static BlockPos findHighestCenterGround(ServerLevel level) {
        int minFeetY = level.getMinBuildHeight() + 1;
        int maxFeetY = level.getMaxBuildHeight() - 2;
        int feetY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 0, 0);

        if (feetY < minFeetY || feetY > maxFeetY) {
            return null;
        }

        BlockPos floor = new BlockPos(0, feetY - 1, 0);
        BlockState floorState = level.getBlockState(floor);
        boolean fluidSurface = !level.getFluidState(floor).isEmpty();
        boolean sturdySurface = floorState.isFaceSturdy(level, floor, net.minecraft.core.Direction.UP);

        if (fluidSurface || !sturdySurface) {
            BlockState platformState = level.dimensionTypeRegistration().is(DimensionType.END_LOCATION)
                    ? Blocks.END_STONE.defaultBlockState()
                    : Blocks.STONE.defaultBlockState();
            buildSurfacePlatform(level, feetY - 1, platformState);
        }

        return new BlockPos(0, feetY, 0);
    }

    /**
     * Replaces the natural center surface at the chosen Y instead of inventing a new
     * platform at world midpoint. This is primarily for water/lava surface spawns.
     */
    private static void buildSurfacePlatform(ServerLevel level, int floorY, BlockState floorState) {
        for (int z = -1; z <= 1; z++) {
            for (int x = -1; x <= 1; x++) {
                level.setBlockAndUpdate(new BlockPos(x, floorY, z), floorState);
            }
        }
    }

    /**
     * Nether policy: use the highest two-block opening in the center column whose
     * floor is solid and is not bedrock. This avoids placing the player on the Nether
     * roof while still preferring the highest natural cavern.
     */
    private static BlockPos findHighestNetherOpening(ServerLevel level) {
        int minY = level.getMinBuildHeight() + 1;
        int maxY = level.getMaxBuildHeight() - 2;
        for (int y = maxY; y >= minY; y--) {
            if (isSafeStandingPosition(level, 0, y, 0, true)) {
                return new BlockPos(0, y, 0);
            }
        }
        return null;
    }

    /**
     * For biomes tagged as underground/cave biomes, prefer an actual cave near the
     * center of the pocket. Search vertically outward from world midpoint and only
     * accept openings that have a solid ceiling reasonably nearby.
     */
    private static BlockPos findCaveOpeningNearCenter(ServerLevel level) {
        int minY = level.getMinBuildHeight() + 1;
        int maxY = level.getMaxBuildHeight() - 2;
        int middleY = minY + (maxY - minY) / 2;
        int verticalRange = maxY - minY;

        for (int deltaY = 0; deltaY <= verticalRange; deltaY++) {
            int upper = middleY + deltaY;
            if (upper <= maxY) {
                BlockPos found = findCaveAtY(level, upper);
                if (found != null) {
                    return found;
                }
            }

            int lower = middleY - deltaY;
            if (deltaY != 0 && lower >= minY) {
                BlockPos found = findCaveAtY(level, lower);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static BlockPos findCaveAtY(ServerLevel level, int y) {
        for (int radius = 0; radius <= 7; radius++) {
            for (int x = -radius; x <= radius; x++) {
                BlockPos candidate = caveCandidate(level, x, y, -radius);
                if (candidate != null) {
                    return candidate;
                }
                if (radius != 0) {
                    candidate = caveCandidate(level, x, y, radius);
                    if (candidate != null) {
                        return candidate;
                    }
                }
            }
            for (int z = -radius + 1; z <= radius - 1; z++) {
                BlockPos candidate = caveCandidate(level, -radius, y, z);
                if (candidate != null) {
                    return candidate;
                }
                if (radius != 0) {
                    candidate = caveCandidate(level, radius, y, z);
                    if (candidate != null) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    private static BlockPos caveCandidate(ServerLevel level, int x, int y, int z) {
        if (!isSafeStandingPosition(level, x, y, z, false) || !hasNearbyCeiling(level, x, y, z)) {
            return null;
        }
        return new BlockPos(x, y, z);
    }

    private static boolean hasNearbyCeiling(ServerLevel level, int x, int y, int z) {
        int maxY = Math.min(level.getMaxBuildHeight() - 1, y + 32);
        for (int testY = y + 2; testY <= maxY; testY++) {
            BlockPos pos = new BlockPos(x, testY, z);
            if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSafeStandingPosition(
            ServerLevel level,
            int x,
            int y,
            int z,
            boolean rejectBedrockFloor) {
        BlockPos feet = new BlockPos(x, y, z);
        BlockPos head = feet.above();
        BlockPos floor = feet.below();
        BlockState floorState = level.getBlockState(floor);
        if (rejectBedrockFloor && floorState.is(Blocks.BEDROCK)) {
            return false;
        }
        return floorState.isFaceSturdy(level, floor, net.minecraft.core.Direction.UP)
                && isPlayerSpaceClear(level, feet)
                && isPlayerSpaceClear(level, head);
    }

    private static boolean isPlayerSpaceClear(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
                && level.getFluidState(pos).isEmpty();
    }

    /**
     * Nether fallback: make a sealed 7x7x7 netherrack shell containing a 5x5x5 air
     * room centered in the middle of the playable area and vertical build range.
     * Building the shell before clearing the room prevents surrounding lava from
     * immediately flowing into the player's fallback chamber.
     */
    private static BlockPos carveProtectedNetherChamber(ServerLevel level) {
        int middleY = level.getMinBuildHeight()
                + (level.getMaxBuildHeight() - level.getMinBuildHeight()) / 2;
        int minShellY = Math.max(level.getMinBuildHeight(), middleY - 3);
        int maxShellY = Math.min(level.getMaxBuildHeight() - 1, middleY + 3);

        for (int y = minShellY; y <= maxShellY; y++) {
            for (int z = -3; z <= 3; z++) {
                for (int x = -3; x <= 3; x++) {
                    if (x == -3 || x == 3 || z == -3 || z == 3 || y == minShellY || y == maxShellY) {
                        level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.NETHERRACK.defaultBlockState());
                    }
                }
            }
        }

        for (int y = minShellY + 1; y <= maxShellY - 1; y++) {
            for (int z = -2; z <= 2; z++) {
                for (int x = -2; x <= 2; x++) {
                    level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
                }
            }
        }

        return new BlockPos(0, minShellY + 1, 0);
    }

    /**
     * Genuine void fallback only. Normal fluid surfaces are handled in-place by
     * buildSurfacePlatform and should never reach this method.
     */
    private static BlockPos buildCenterPlatform(ServerLevel level, BlockState floorState) {
        int minFeetY = level.getMinBuildHeight() + 1;
        int maxFeetY = level.getMaxBuildHeight() - 2;
        int feetY = Math.max(minFeetY, Math.min(maxFeetY,
                level.getMinBuildHeight() + (level.getMaxBuildHeight() - level.getMinBuildHeight()) / 2));

        for (int z = -1; z <= 1; z++) {
            for (int x = -1; x <= 1; x++) {
                level.setBlockAndUpdate(new BlockPos(x, feetY - 1, z), floorState);
            }
        }
        level.setBlockAndUpdate(new BlockPos(0, feetY, 0), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(0, feetY + 1, 0), Blocks.AIR.defaultBlockState());
        return new BlockPos(0, feetY, 0);
    }

    @SuppressWarnings({"deprecation", "removal"})
    private static boolean isUndergroundBiome(Holder<Biome> biome) {
        if (biome.is(Tags.Biomes.IS_UNDERGROUND)) {
            return true;
        }
        Optional<ResourceKey<Biome>> key = biome.unwrapKey();
        return key.isPresent() && BiomeDictionary.hasType(key.get(), BiomeDictionary.Type.UNDERGROUND);
    }

    private static boolean isPocketLevel(ResourceKey<Level> key) {
        return BiomePockets.MOD_ID.equals(key.location().getNamespace())
                && key.location().getPath().startsWith("pocket_");
    }
}
