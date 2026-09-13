package com.akitaattribute.biomepockets.world;

import com.akitaattribute.biomepockets.BiomePockets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Owns the player-facing permanent-pocket state. A player may claim exactly one
 * BiomePockets dimension. Claimed dimensions are kept alive when empty, and the
 * owner's last pocket location plus Visit/Exit return point are persisted separately
 * from the runtime dimension metadata.
 */
public final class PocketClaimManager {
    private static final String CLAIM_DIR = "biomepockets_claims";
    private static final String CLAIM_SUFFIX = ".properties";
    private static final String GEOMETRY_FILE = ".biomepockets-geometry.properties";
    private static final String OWNERSHIP_MARKER = ".biomepockets-owned";
    private static final int DATA_VERSION = 1;

    // Raw experience required to go from level 0 to level 30 in vanilla Minecraft.
    // Expansion N costs BASE_EXPANSION_XP * N raw XP, rather than removing 30 level
    // numbers from the player's current level each time.
    public static final int BASE_EXPANSION_XP = 1395;

    private static final Map<UUID, ClaimRecord> CLAIMS = new HashMap<>();
    private static final Map<ResourceKey<Level>, UUID> OWNERS = new HashMap<>();
    private static final Set<UUID> EXPANDING = new HashSet<>();

    private PocketClaimManager() { }

    public enum Action {
        CLAIM,
        UNCLAIM,
        EXPAND,
        VISIT,
        EXIT
    }

    public record ManagerState(
            boolean hasClaim,
            boolean canClaim,
            boolean canUnclaim,
            boolean canExpand,
            boolean canVisit,
            boolean canExit,
            boolean expanding,
            boolean creative,
            int expansionCost,
            int availableXp,
            int currentSize,
            int nextSize,
            String claimedDimension) { }

    public static ManagerState stateFor(ServerPlayer player) {
        UUID playerId = player.getUUID();
        ClaimRecord claim = CLAIMS.get(playerId);
        boolean expanding = EXPANDING.contains(playerId);
        boolean creative = player.getAbilities().instabuild;
        int availableXp = Math.max(0, player.totalExperience);

        boolean currentIsPocket = PocketDimensionManager.isOwned(player.getLevel().dimension());
        boolean currentClaimedByAnyone = OWNERS.containsKey(player.getLevel().dimension());
        boolean canClaim = claim == null && currentIsPocket && !currentClaimedByAnyone;

        if (claim == null) {
            return new ManagerState(
                    false,
                    canClaim,
                    false,
                    false,
                    false,
                    false,
                    false,
                    creative,
                    0,
                    availableXp,
                    0,
                    0,
                    "");
        }

        int cost = expansionCost(claim.expansions);
        int currentSize = claim.radius * 2 + 1;
        int nextSize = currentSize + 2;
        boolean inClaim = player.getLevel().dimension().equals(claim.dimension);
        boolean claimLoaded = player.getServer().getLevel(claim.dimension) != null;

        return new ManagerState(
                true,
                false,
                !expanding,
                !expanding && claimLoaded && (creative || availableXp >= cost),
                !expanding && claimLoaded && !inClaim,
                !expanding && inClaim && claim.returnPoint != null,
                expanding,
                creative,
                cost,
                availableXp,
                currentSize,
                nextSize,
                claim.dimension.location().toString());
    }

    public static void handleAction(ServerPlayer player, Action action) {
        switch (action) {
            case CLAIM -> claimCurrentPocket(player);
            case UNCLAIM -> unclaim(player);
            case EXPAND -> expand(player);
            case VISIT -> visit(player);
            case EXIT -> exit(player);
        }
    }

    public static boolean isClaimed(ResourceKey<Level> dimension) {
        return OWNERS.containsKey(dimension);
    }

    /**
     * A temporary pocket used as the source of Visit must remain available until Exit
     * can return the player to it. This protects that one dimension from the ordinary
     * empty-pocket teardown while it is referenced by a persisted return point.
     */
    public static boolean isProtectedReturnDimension(ResourceKey<Level> dimension) {
        return CLAIMS.values().stream()
                .anyMatch(claim -> claim.returnPoint != null && claim.returnPoint.dimension.equals(dimension));
    }

    public static void handleDimensionChange(
            ServerPlayer player,
            ResourceKey<Level> from,
            ResourceKey<Level> to) {
        ClaimRecord claim = CLAIMS.get(player.getUUID());
        if (claim == null) {
            return;
        }

        if (from.equals(claim.dimension) && !to.equals(claim.dimension)) {
            // Forge's event is fired after the level switch, so the player's current
            // coordinates are already the destination coordinates. The manager actions
            // explicitly record the pocket coordinate before teleporting. For an
            // arbitrary/manual departure, use the last position captured just before
            // the switch by the player entity's old-position fields as a best effort.
            if (!claim.intentionalExit) {
                ServerLevel departed = player.getServer().getLevel(from);
                if (departed != null) {
                    // The manager's normal Exit path has already written an exact point.
                    // For command/portal departures, retain the most recent exact point
                    // we have instead of accidentally replacing it with destination XYZ.
                    saveClaim(player.getServer(), claim);
                }
            }
            claim.intentionalExit = false;
        }
    }

    /** Records an exact pocket position before an external departure if an event hook
     * has access to it. Manager Exit always calls this before teleporting. */
    public static void rememberCurrentPocketPosition(ServerPlayer player) {
        ClaimRecord claim = CLAIMS.get(player.getUUID());
        if (claim == null || !player.getLevel().dimension().equals(claim.dimension)) {
            return;
        }
        claim.lastX = player.getX();
        claim.lastY = player.getY();
        claim.lastZ = player.getZ();
        claim.lastYaw = player.getYRot();
        claim.lastPitch = player.getXRot();
        saveClaim(player.getServer(), claim);
    }

    private static void claimCurrentPocket(ServerPlayer player) {
        UUID playerId = player.getUUID();
        if (CLAIMS.containsKey(playerId)) {
            message(player, "You already have a claimed biome pocket.");
            return;
        }

        ResourceKey<Level> dimension = player.getLevel().dimension();
        if (!PocketDimensionManager.isOwned(dimension)) {
            message(player, "You must be inside a BiomePockets pocket to claim it.");
            return;
        }
        if (OWNERS.containsKey(dimension)) {
            message(player, "That biome pocket is already claimed.");
            return;
        }

        ServerLevel level = player.getLevel();
        Optional<ResourceLocation> biomeId = pocketBiomeId(level);
        if (biomeId.isEmpty()) {
            message(player, "Could not determine this pocket's biome.");
            return;
        }

        Geometry geometry = readGeometry(level.getServer(), dimension)
                .orElseGet(() -> geometryFromGenerator(level));
        ClaimRecord claim = new ClaimRecord(
                playerId,
                dimension,
                biomeId.get(),
                geometry.seed,
                geometry.radius,
                Math.max(0, geometry.radius - 1),
                player.getX(),
                player.getY(),
                player.getZ(),
                player.getYRot(),
                player.getXRot(),
                null);

        CLAIMS.put(playerId, claim);
        OWNERS.put(dimension, playerId);
        writeGeometry(level.getServer(), dimension, geometry.radius, geometry.seed);
        saveClaim(level.getServer(), claim);
        message(player, "Claimed this biome pocket as your permanent pocket.");
    }

    private static void unclaim(ServerPlayer player) {
        UUID playerId = player.getUUID();
        if (EXPANDING.contains(playerId)) {
            message(player, "You cannot unclaim a pocket while it is expanding.");
            return;
        }

        ClaimRecord claim = CLAIMS.remove(playerId);
        if (claim == null) {
            message(player, "You do not have a claimed biome pocket.");
            return;
        }

        OWNERS.remove(claim.dimension, playerId);
        ResourceKey<Level> protectedReturn = claim.returnPoint == null ? null : claim.returnPoint.dimension;
        deleteClaimFile(player.getServer(), playerId);
        message(player, "Your biome pocket is no longer claimed.");

        // If the player is not standing in it, it resumes normal temporary-pocket
        // lifetime immediately. If they are inside it, the normal departure event will
        // tear it down after they leave.
        PocketDimensionManager.teardownIfEmpty(player.getServer(), claim.dimension);
        if (protectedReturn != null && !isProtectedReturnDimension(protectedReturn)) {
            PocketDimensionManager.teardownIfEmpty(player.getServer(), protectedReturn);
        }
    }

    private static void visit(ServerPlayer player) {
        ClaimRecord claim = CLAIMS.get(player.getUUID());
        if (claim == null) {
            message(player, "You do not have a claimed biome pocket.");
            return;
        }
        if (EXPANDING.contains(player.getUUID())) {
            message(player, "Your biome pocket is currently expanding.");
            return;
        }
        if (player.getLevel().dimension().equals(claim.dimension)) {
            message(player, "You are already inside your claimed biome pocket.");
            return;
        }

        ServerLevel pocket = player.getServer().getLevel(claim.dimension);
        if (pocket == null) {
            message(player, "Your claimed biome pocket is not currently available.");
            return;
        }

        claim.returnPoint = new ReturnPoint(
                player.getLevel().dimension(),
                player.getX(),
                player.getY(),
                player.getZ(),
                player.getYRot(),
                player.getXRot());
        saveClaim(player.getServer(), claim);

        player.teleportTo(
                pocket,
                claim.lastX,
                claim.lastY,
                claim.lastZ,
                claim.lastYaw,
                claim.lastPitch);
    }

    private static void exit(ServerPlayer player) {
        ClaimRecord claim = CLAIMS.get(player.getUUID());
        if (claim == null || !player.getLevel().dimension().equals(claim.dimension)) {
            message(player, "You must be inside your claimed biome pocket to use Exit.");
            return;
        }
        if (EXPANDING.contains(player.getUUID())) {
            message(player, "Your biome pocket is currently expanding.");
            return;
        }
        if (claim.returnPoint == null) {
            message(player, "There is no saved location to return to. Use Visit to enter the pocket first.");
            return;
        }

        claim.lastX = player.getX();
        claim.lastY = player.getY();
        claim.lastZ = player.getZ();
        claim.lastYaw = player.getYRot();
        claim.lastPitch = player.getXRot();
        claim.intentionalExit = true;

        ReturnPoint returnPoint = claim.returnPoint;
        ServerLevel destination = player.getServer().getLevel(returnPoint.dimension);
        if (destination == null) {
            claim.intentionalExit = false;
            message(player, "The dimension you entered from is no longer available.");
            saveClaim(player.getServer(), claim);
            return;
        }

        claim.returnPoint = null;
        saveClaim(player.getServer(), claim);
        player.teleportTo(
                destination,
                returnPoint.x,
                returnPoint.y,
                returnPoint.z,
                returnPoint.yaw,
                returnPoint.pitch);
    }

    private static void expand(ServerPlayer player) {
        UUID playerId = player.getUUID();
        ClaimRecord claim = CLAIMS.get(playerId);
        if (claim == null) {
            message(player, "You must claim a biome pocket before expanding it.");
            return;
        }
        if (!EXPANDING.add(playerId)) {
            message(player, "Your biome pocket is already expanding.");
            return;
        }

        int cost = expansionCost(claim.expansions);
        if (!player.getAbilities().instabuild && player.totalExperience < cost) {
            EXPANDING.remove(playerId);
            message(player, "You need " + cost + " experience points to expand this pocket.");
            return;
        }

        int newRadius = claim.radius + 1;
        int oldSize = claim.radius * 2 + 1;
        int newSize = newRadius * 2 + 1;
        message(player, "Preparing biome pocket expansion " + oldSize + "x" + oldSize
                + " -> " + newSize + "x" + newSize + "...");

        PocketExpansionManager.expand(
                player,
                claim.dimension,
                claim.biome,
                claim.seed,
                claim.radius,
                newRadius,
                cost);
    }

    static void completeExpansion(
            MinecraftServer server,
            UUID playerId,
            ResourceKey<Level> oldDimension,
            ResourceKey<Level> newDimension,
            int newRadius,
            int cost) {
        ClaimRecord claim = CLAIMS.get(playerId);
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (claim == null || !claim.dimension.equals(oldDimension) || !EXPANDING.contains(playerId)) {
            PocketDimensionManager.teardownIfEmpty(server, newDimension);
            return;
        }
        if (player == null) {
            EXPANDING.remove(playerId);
            PocketDimensionManager.teardownIfEmpty(server, newDimension);
            return;
        }
        if (!player.getAbilities().instabuild && player.totalExperience < cost) {
            EXPANDING.remove(playerId);
            PocketDimensionManager.teardownIfEmpty(server, newDimension);
            message(player, "Expansion cancelled because you no longer have the required experience.");
            return;
        }

        ServerLevel newLevel = server.getLevel(newDimension);
        if (newLevel == null) {
            EXPANDING.remove(playerId);
            message(player, "Expansion failed because the expanded pocket is unavailable.");
            return;
        }

        boolean playerInsideOld = player.getLevel().dimension().equals(oldDimension);
        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();
        float yaw = player.getYRot();
        float pitch = player.getXRot();

        OWNERS.remove(oldDimension, playerId);
        claim.dimension = newDimension;
        claim.radius = newRadius;
        claim.expansions++;
        OWNERS.put(newDimension, playerId);
        EXPANDING.remove(playerId);

        if (!player.getAbilities().instabuild) {
            player.giveExperiencePoints(-cost);
        }
        saveClaim(server, claim);

        if (playerInsideOld) {
            player.teleportTo(newLevel, x, y, z, yaw, pitch);
        }

        PocketDimensionManager.teardownIfEmpty(server, oldDimension);
        int size = newRadius * 2 + 1;
        message(player, "Expanded your claimed biome pocket to " + size + "x" + size
                + " chunks for " + cost + " experience points.");
    }

    static void expansionFailed(MinecraftServer server, UUID playerId, String reason) {
        EXPANDING.remove(playerId);
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player != null) {
            message(player, reason);
        }
    }

    public static void load(MinecraftServer server) {
        CLAIMS.clear();
        OWNERS.clear();
        EXPANDING.clear();

        Path directory = claimDirectory(server);
        if (!Files.isDirectory(directory)) {
            return;
        }

        int loaded = 0;
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                if (!file.getFileName().toString().endsWith(CLAIM_SUFFIX)) {
                    continue;
                }
                ClaimRecord claim = loadClaim(server, file);
                if (claim == null) {
                    continue;
                }
                if (CLAIMS.containsKey(claim.owner) || OWNERS.containsKey(claim.dimension)) {
                    BiomePockets.LOGGER.warn("Ignoring duplicate BiomePockets claim {}", file);
                    continue;
                }
                CLAIMS.put(claim.owner, claim);
                OWNERS.put(claim.dimension, claim.owner);
                loaded++;
            }
        } catch (IOException exception) {
            BiomePockets.LOGGER.warn("Could not load BiomePockets claims from {}", directory, exception);
        }

        BiomePockets.LOGGER.info("Loaded {} permanent BiomePockets claim(s)", loaded);
    }

    public static void saveAll(MinecraftServer server) {
        for (ClaimRecord claim : new ArrayList<>(CLAIMS.values())) {
            saveClaim(server, claim);
        }
    }

    private static ClaimRecord loadClaim(MinecraftServer server, Path file) {
        Properties properties = loadProperties(file);
        if (properties == null) {
            return null;
        }

        try {
            int version = Integer.parseInt(properties.getProperty("version", "0"));
            UUID owner = UUID.fromString(properties.getProperty("owner"));
            if (version != DATA_VERSION
                    || !file.getFileName().toString().equals(owner + CLAIM_SUFFIX)) {
                return null;
            }

            ResourceLocation dimensionId = new ResourceLocation(properties.getProperty("dimension"));
            if (!isPocketId(dimensionId)) {
                return null;
            }
            ResourceKey<Level> dimension = ResourceKey.create(Registry.DIMENSION_REGISTRY, dimensionId);
            if (!PocketDimensionManager.isOwned(dimension)) {
                BiomePockets.LOGGER.warn(
                        "Claim {} references unavailable pocket {}; leaving claim file untouched",
                        owner,
                        dimensionId);
                return null;
            }

            ResourceLocation biome = new ResourceLocation(properties.getProperty("biome"));
            long seed = Long.parseLong(properties.getProperty("seed"));
            int radius = Math.max(1, Integer.parseInt(properties.getProperty("radius", "1")));
            int expansions = Math.max(0, Integer.parseInt(properties.getProperty("expansions", Integer.toString(radius - 1))));

            ReturnPoint returnPoint = null;
            String returnDimension = properties.getProperty("return_dimension", "");
            if (!returnDimension.isBlank()) {
                returnPoint = new ReturnPoint(
                        ResourceKey.create(Registry.DIMENSION_REGISTRY, new ResourceLocation(returnDimension)),
                        Double.parseDouble(properties.getProperty("return_x")),
                        Double.parseDouble(properties.getProperty("return_y")),
                        Double.parseDouble(properties.getProperty("return_z")),
                        Float.parseFloat(properties.getProperty("return_yaw")),
                        Float.parseFloat(properties.getProperty("return_pitch")));
            }

            return new ClaimRecord(
                    owner,
                    dimension,
                    biome,
                    seed,
                    radius,
                    expansions,
                    Double.parseDouble(properties.getProperty("last_x")),
                    Double.parseDouble(properties.getProperty("last_y")),
                    Double.parseDouble(properties.getProperty("last_z")),
                    Float.parseFloat(properties.getProperty("last_yaw")),
                    Float.parseFloat(properties.getProperty("last_pitch")),
                    returnPoint);
        } catch (Exception exception) {
            BiomePockets.LOGGER.warn("Ignoring malformed BiomePockets claim {}", file, exception);
            return null;
        }
    }

    private static void saveClaim(MinecraftServer server, ClaimRecord claim) {
        Path directory = claimDirectory(server);
        Path file = directory.resolve(claim.owner + CLAIM_SUFFIX).normalize();
        if (!file.startsWith(directory)) {
            return;
        }

        Properties properties = new Properties();
        properties.setProperty("version", Integer.toString(DATA_VERSION));
        properties.setProperty("owner", claim.owner.toString());
        properties.setProperty("dimension", claim.dimension.location().toString());
        properties.setProperty("biome", claim.biome.toString());
        properties.setProperty("seed", Long.toString(claim.seed));
        properties.setProperty("radius", Integer.toString(claim.radius));
        properties.setProperty("expansions", Integer.toString(claim.expansions));
        properties.setProperty("last_x", Double.toString(claim.lastX));
        properties.setProperty("last_y", Double.toString(claim.lastY));
        properties.setProperty("last_z", Double.toString(claim.lastZ));
        properties.setProperty("last_yaw", Float.toString(claim.lastYaw));
        properties.setProperty("last_pitch", Float.toString(claim.lastPitch));

        if (claim.returnPoint != null) {
            properties.setProperty("return_dimension", claim.returnPoint.dimension.location().toString());
            properties.setProperty("return_x", Double.toString(claim.returnPoint.x));
            properties.setProperty("return_y", Double.toString(claim.returnPoint.y));
            properties.setProperty("return_z", Double.toString(claim.returnPoint.z));
            properties.setProperty("return_yaw", Float.toString(claim.returnPoint.yaw));
            properties.setProperty("return_pitch", Float.toString(claim.returnPoint.pitch));
        }

        try {
            Files.createDirectories(directory);
            try (OutputStream output = Files.newOutputStream(
                    file,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                properties.store(output, "BiomePockets permanent pocket claim");
            }
        } catch (IOException exception) {
            BiomePockets.LOGGER.warn("Could not save permanent pocket claim for {}", claim.owner, exception);
        }
    }

    private static void deleteClaimFile(MinecraftServer server, UUID owner) {
        Path directory = claimDirectory(server);
        Path file = directory.resolve(owner + CLAIM_SUFFIX).normalize();
        if (!file.startsWith(directory) || file.equals(directory)) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException exception) {
            BiomePockets.LOGGER.warn("Could not delete pocket claim file {}", file, exception);
        }
    }

    private static Optional<ResourceLocation> pocketBiomeId(ServerLevel level) {
        int probeY = level.getMinBuildHeight()
                + Math.max(1, (level.getMaxBuildHeight() - level.getMinBuildHeight()) / 2);
        Holder<Biome> biome = level.getBiome(new BlockPos(0, probeY, 0));
        return biome.unwrapKey().map(ResourceKey::location);
    }

    private static Geometry geometryFromGenerator(ServerLevel level) {
        Object generator = level.getChunkSource().getGenerator();
        if (generator instanceof BoundedNoiseBasedChunkGenerator) {
            try {
                Field seedField = BoundedNoiseBasedChunkGenerator.class.getDeclaredField("pocketSeed");
                Field radiusField = BoundedNoiseBasedChunkGenerator.class.getDeclaredField("maxPocketChunk");
                seedField.setAccessible(true);
                radiusField.setAccessible(true);
                return new Geometry(
                        Math.max(1, Math.abs(radiusField.getInt(generator))),
                        seedField.getLong(generator));
            } catch (ReflectiveOperationException exception) {
                BiomePockets.LOGGER.warn("Could not read pocket geometry from bounded generator", exception);
            }
        }
        long fallbackSeed = level.getServer().getWorldData().worldGenSettings().seed()
                ^ level.dimension().location().hashCode();
        return new Geometry(1, fallbackSeed);
    }

    private static Optional<Geometry> readGeometry(MinecraftServer server, ResourceKey<Level> dimension) {
        Path folder = pocketFolder(server, dimension);
        if (!hasValidOwnershipMarker(dimension, folder)) {
            return Optional.empty();
        }
        Path file = folder.resolve(GEOMETRY_FILE).normalize();
        if (!file.startsWith(folder) || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        Properties properties = loadProperties(file);
        if (properties == null) {
            return Optional.empty();
        }
        try {
            if (!properties.getProperty("dimension", "").equals(dimension.location().toString())) {
                return Optional.empty();
            }
            return Optional.of(new Geometry(
                    Math.max(1, Integer.parseInt(properties.getProperty("radius", "1"))),
                    Long.parseLong(properties.getProperty("seed"))));
        } catch (Exception exception) {
            return Optional.empty();
        }
    }

    static void writeGeometry(MinecraftServer server, ResourceKey<Level> dimension, int radius, long seed) {
        Path folder = pocketFolder(server, dimension);
        if (!hasValidOwnershipMarker(dimension, folder)) {
            return;
        }
        Path file = folder.resolve(GEOMETRY_FILE).normalize();
        if (!file.startsWith(folder)) {
            return;
        }

        Properties properties = new Properties();
        properties.setProperty("version", Integer.toString(DATA_VERSION));
        properties.setProperty("dimension", dimension.location().toString());
        properties.setProperty("radius", Integer.toString(Math.max(1, radius)));
        properties.setProperty("seed", Long.toString(seed));
        try (OutputStream output = Files.newOutputStream(
                file,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            properties.store(output, "BiomePockets generated pocket geometry");
        } catch (IOException exception) {
            BiomePockets.LOGGER.warn("Could not persist pocket geometry for {}", dimension.location(), exception);
        }
    }

    private static Properties loadProperties(Path file) {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
            return properties;
        } catch (IOException exception) {
            return null;
        }
    }

    private static boolean hasValidOwnershipMarker(ResourceKey<Level> dimension, Path folder) {
        if (!isPocketId(dimension.location())) {
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

    private static Path claimDirectory(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT)
                .resolve("data")
                .resolve(CLAIM_DIR)
                .toAbsolutePath()
                .normalize();
    }

    private static Path pocketFolder(MinecraftServer server, ResourceKey<Level> dimension) {
        return DimensionType.getStorageFolder(dimension, server.getWorldPath(LevelResource.ROOT))
                .toAbsolutePath()
                .normalize();
    }

    private static boolean isPocketId(ResourceLocation id) {
        return BiomePockets.MOD_ID.equals(id.getNamespace()) && id.getPath().startsWith("pocket_");
    }

    private static int expansionCost(int completedExpansions) {
        long multiplier = (long) Math.max(0, completedExpansions) + 1L;
        return (int) Math.min(Integer.MAX_VALUE, BASE_EXPANSION_XP * multiplier);
    }

    private static void message(ServerPlayer player, String text) {
        player.displayClientMessage(new TextComponent(text), false);
    }

    private static final class ClaimRecord {
        private final UUID owner;
        private ResourceKey<Level> dimension;
        private final ResourceLocation biome;
        private final long seed;
        private int radius;
        private int expansions;
        private double lastX;
        private double lastY;
        private double lastZ;
        private float lastYaw;
        private float lastPitch;
        private ReturnPoint returnPoint;
        private boolean intentionalExit;

        private ClaimRecord(
                UUID owner,
                ResourceKey<Level> dimension,
                ResourceLocation biome,
                long seed,
                int radius,
                int expansions,
                double lastX,
                double lastY,
                double lastZ,
                float lastYaw,
                float lastPitch,
                ReturnPoint returnPoint) {
            this.owner = owner;
            this.dimension = dimension;
            this.biome = biome;
            this.seed = seed;
            this.radius = radius;
            this.expansions = expansions;
            this.lastX = lastX;
            this.lastY = lastY;
            this.lastZ = lastZ;
            this.lastYaw = lastYaw;
            this.lastPitch = lastPitch;
            this.returnPoint = returnPoint;
        }
    }

    private record ReturnPoint(
            ResourceKey<Level> dimension,
            double x,
            double y,
            double z,
            float yaw,
            float pitch) { }

    private record Geometry(int radius, long seed) { }
}
