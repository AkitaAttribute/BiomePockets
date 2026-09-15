package com.akitaattribute.biomepockets.world;

import com.akitaattribute.biomepockets.BiomePockets;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.BiomeDictionary;
import net.minecraftforge.common.Tags;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Chooses a random seed whose native biome and simple terrain height are suitable for
 * the requested pocket biome before the actual pocket locks generation to that biome.
 *
 * Seed testing intentionally stays much cheaper than real chunk generation. By default
 * only the exact center of the center chunk is sampled. Optional debug probes sample
 * additional chunk centers across the pocket footprint. Rejected coherent seeds are
 * retained in strict FIFO buckets for the biome they naturally fit.
 */
public final class PocketSeedBroker {
    private static final String CACHE_FILE = "biomepockets-seed-cache-v3.tsv";
    private static final int MAX_RANDOM_ATTEMPTS = 96;
    private static final int NORMAL_LAND_MIN_Y = 64;
    private static final int NORMAL_LAND_MAX_Y = 80;

    private static final int SEARCH_PARALLELISM = Math.max(
            1,
            Math.min(4, Runtime.getRuntime().availableProcessors()));
    private static final AtomicInteger SEARCH_THREAD_IDS = new AtomicInteger();
    private static final ExecutorService SEARCH_EXECUTOR = Executors.newFixedThreadPool(
            SEARCH_PARALLELISM,
            runnable -> {
                Thread thread = new Thread(
                        runnable,
                        "BiomePockets-SeedSearch-" + SEARCH_THREAD_IDS.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });

    private static final Map<BucketKey, Deque<Long>> BUCKETS = new LinkedHashMap<>();
    private static Path loadedCachePath;

    private PocketSeedBroker() { }

    public static synchronized long selectSeed(
            MinecraftServer server,
            Holder<Biome> targetBiome,
            int radius) {
        ResourceLocation targetId = biomeId(server, targetBiome);
        if (targetId == null) {
            return randomSeed();
        }

        Profile profile = profileFor(targetBiome);
        ServerLevel referenceLevel = referenceLevel(server, profile);
        if (referenceLevel == null) {
            BiomePockets.LOGGER.warn(
                    "No {} reference level is available for {}; using an unvalidated random pocket seed",
                    profile,
                    targetId);
            return randomSeed();
        }

        ensureLoaded(server);
        BucketKey targetBucket = new BucketKey(profile, radius, targetId);

        // Cached seeds remain strict FIFO. The oldest entry is removed and revalidated
        // under the CURRENT debug probe count before a later cached seed can be used.
        Deque<Long> cached = BUCKETS.get(targetBucket);
        if (cached != null) {
            while (!cached.isEmpty()) {
                long seed = cached.removeFirst();
                Candidate candidate = classify(referenceLevel, profile, targetId, radius, seed);
                if (candidate.matchesTarget()) {
                    pruneEmptyBucket(targetBucket);
                    save(server);
                    BiomePockets.LOGGER.info(
                            "Pocket seed broker consumed FIFO seed {} for {} {}x{}; probes={}, biome={}/{}, terrain={}/{}, centerY={}",
                            seed,
                            targetId,
                            radius * 2 + 1,
                            radius * 2 + 1,
                            candidate.sampleCount(),
                            candidate.targetMatches(),
                            candidate.sampleCount(),
                            candidate.targetTerrainMatches(),
                            candidate.sampleCount(),
                            candidate.centerTerrainY());
                    return seed;
                }

                if (candidate.qualifiedForDominant()
                        && candidate.dominantBiome() != null
                        && !candidate.dominantBiome().equals(targetId)) {
                    enqueue(new BucketKey(profile, radius, candidate.dominantBiome()), seed);
                }
            }
            pruneEmptyBucket(targetBucket);
        }

        List<Candidate> rejected = new ArrayList<>();
        Candidate bestTerrainCompatible = null;
        int evaluated = 0;

        while (evaluated < MAX_RANDOM_ATTEMPTS) {
            int batchSize = Math.min(SEARCH_PARALLELISM, MAX_RANDOM_ATTEMPTS - evaluated);
            List<Candidate> batch = classifyRandomBatch(
                    referenceLevel,
                    profile,
                    targetId,
                    radius,
                    batchSize);

            Candidate selected = null;
            int selectedIndex = -1;
            for (int index = 0; index < batch.size(); index++) {
                Candidate candidate = batch.get(index);
                if (selected == null && candidate.matchesTarget()) {
                    selected = candidate;
                    selectedIndex = index;
                    continue;
                }

                rejected.add(candidate);
                if (candidate.targetTerrainCompatible()
                        && (bestTerrainCompatible == null
                        || candidate.targetScore() > bestTerrainCompatible.targetScore())) {
                    bestTerrainCompatible = candidate;
                }
            }

            int batchStart = evaluated;
            evaluated += batch.size();
            if (selected != null) {
                enqueueRejected(profile, radius, rejected, selected.seed());
                save(server);
                BiomePockets.LOGGER.info(
                        "Pocket seed broker matched {} {}x{} at candidate {} ({} evaluated, {} probe(s)/seed): seed={}, biome={}/{}, terrain={}/{}, centerY={}",
                        targetId,
                        radius * 2 + 1,
                        radius * 2 + 1,
                        batchStart + selectedIndex + 1,
                        evaluated,
                        selected.sampleCount(),
                        selected.seed(),
                        selected.targetMatches(),
                        selected.sampleCount(),
                        selected.targetTerrainMatches(),
                        selected.sampleCount(),
                        selected.centerTerrainY());
                return selected.seed();
            }
        }

        // Do not knowingly turn an ordinary land biome into ocean or mountain terrain.
        if (bestTerrainCompatible != null) {
            long selected = bestTerrainCompatible.seed();
            enqueueRejected(profile, radius, rejected, selected);
            save(server);
            BiomePockets.LOGGER.warn(
                    "Pocket seed broker did not find a full {} climate match in {} candidates; using terrain-safe seed {} (probes={}, biome={}/{}, terrain={}/{}, centerY={})",
                    targetId,
                    MAX_RANDOM_ATTEMPTS,
                    selected,
                    bestTerrainCompatible.sampleCount(),
                    bestTerrainCompatible.targetMatches(),
                    bestTerrainCompatible.sampleCount(),
                    bestTerrainCompatible.targetTerrainMatches(),
                    bestTerrainCompatible.sampleCount(),
                    bestTerrainCompatible.centerTerrainY());
            return selected;
        }

        // Extremely unusual fallback: continue searching until the height class is at
        // least suitable even if the exact requested biome does not naturally resolve.
        int fallbackEvaluated = 0;
        while (fallbackEvaluated < MAX_RANDOM_ATTEMPTS) {
            int batchSize = Math.min(SEARCH_PARALLELISM, MAX_RANDOM_ATTEMPTS - fallbackEvaluated);
            List<Candidate> batch = classifyRandomBatch(
                    referenceLevel,
                    profile,
                    targetId,
                    radius,
                    batchSize);

            Candidate selected = null;
            int selectedIndex = -1;
            for (int index = 0; index < batch.size(); index++) {
                Candidate candidate = batch.get(index);
                if (selected == null && candidate.targetTerrainCompatible()) {
                    selected = candidate;
                    selectedIndex = index;
                    continue;
                }
                rejected.add(candidate);
            }

            int batchStart = fallbackEvaluated;
            fallbackEvaluated += batch.size();
            if (selected != null) {
                enqueueRejected(profile, radius, rejected, selected.seed());
                save(server);
                BiomePockets.LOGGER.warn(
                        "Pocket seed broker required terrain-only fallback for {} at additional candidate {} ({} evaluated): seed={}, centerY={}",
                        targetId,
                        batchStart + selectedIndex + 1,
                        fallbackEvaluated,
                        selected.seed(),
                        selected.centerTerrainY());
                return selected.seed();
            }
        }

        BiomePockets.LOGGER.error(
                "Pocket seed broker could not find terrain compatible with {} after {} candidates; using final random seed",
                targetId,
                MAX_RANDOM_ATTEMPTS * 2);
        return randomSeed();
    }

    /**
     * Independent candidate seeds are safe to test concurrently because this does not
     * submit real chunks. Results are returned in creation order so FIFO bucketing stays
     * deterministic even when worker completion order differs.
     */
    private static List<Candidate> classifyRandomBatch(
            ServerLevel referenceLevel,
            Profile profile,
            ResourceLocation targetId,
            int radius,
            int count) {
        List<CompletableFuture<Candidate>> futures = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            long seed = randomSeed();
            futures.add(CompletableFuture.supplyAsync(
                    () -> classify(referenceLevel, profile, targetId, radius, seed),
                    SEARCH_EXECUTOR));
        }

        List<Candidate> result = new ArrayList<>(count);
        for (CompletableFuture<Candidate> future : futures) {
            result.add(future.join());
        }
        return result;
    }

    private static Candidate classify(
            ServerLevel referenceLevel,
            Profile profile,
            ResourceLocation targetId,
            int radius,
            long seed) {
        try {
            ChunkGenerator generator = referenceLevel.getChunkSource().getGenerator().withSeed(seed);
            int[][] probes = probeChunkOffsets(radius, PocketDebugSettings.heightProbes());
            int sampleCount = probes.length;
            int required = sampleCount / 2 + 1;

            Map<ResourceLocation, Integer> counts = new LinkedHashMap<>();
            int[] terrainY = new int[sampleCount];
            ResourceLocation centerBiome = null;
            int centerTerrainY = Integer.MIN_VALUE;
            boolean centerTerrainMatches = profile != Profile.OVERWORLD;
            int targetMatches = 0;
            int targetTerrainMatches = 0;

            for (int index = 0; index < probes.length; index++) {
                int chunkX = probes[index][0];
                int chunkZ = probes[index][1];
                int blockX = chunkX * 16 + 8;
                int blockZ = chunkZ * 16 + 8;

                int baseHeight = generator.getBaseHeight(
                        blockX,
                        blockZ,
                        profile == Profile.OVERWORLD
                                ? Heightmap.Types.OCEAN_FLOOR_WG
                                : Heightmap.Types.WORLD_SURFACE_WG,
                        referenceLevel);

                // getBaseHeight is the first block above the surface. Convert it to the
                // Y of the actual top non-fluid terrain block for the 64/80 tests.
                int topSolidY = baseHeight - 1;
                terrainY[index] = topSolidY;

                int biomeY = profile == Profile.OVERWORLD
                        ? Math.max(baseHeight, NORMAL_LAND_MIN_Y)
                        : baseHeight;
                biomeY = Math.max(
                        referenceLevel.getMinBuildHeight() + 1,
                        Math.min(referenceLevel.getMaxBuildHeight() - 1, biomeY));

                Holder<Biome> natural = generator.getNoiseBiome(
                        QuartPos.fromBlock(blockX),
                        QuartPos.fromBlock(biomeY),
                        QuartPos.fromBlock(blockZ));
                ResourceLocation naturalId = natural.unwrapKey()
                        .map(ResourceKey::location)
                        .orElse(null);
                if (naturalId != null) {
                    counts.merge(naturalId, 1, Integer::sum);
                    if (naturalId.equals(targetId)) {
                        targetMatches++;
                    }
                }

                boolean terrainMatches = profile != Profile.OVERWORLD
                        || terrainMatches(targetId, topSolidY);
                if (terrainMatches) {
                    targetTerrainMatches++;
                }

                // The first probe is always the exact center chunk.
                if (index == 0) {
                    centerBiome = naturalId;
                    centerTerrainY = topSolidY;
                    centerTerrainMatches = terrainMatches;
                }
            }

            ResourceLocation dominant = centerBiome;
            int dominantCount = dominant == null ? 0 : counts.getOrDefault(dominant, 0);
            for (Map.Entry<ResourceLocation, Integer> entry : counts.entrySet()) {
                if (entry.getValue() > dominantCount) {
                    dominant = entry.getKey();
                    dominantCount = entry.getValue();
                }
            }

            boolean centerMatches = targetId.equals(centerBiome);
            boolean targetTerrainCompatible = centerTerrainMatches
                    && targetTerrainMatches >= required;
            boolean matchesTarget = centerMatches
                    && targetMatches >= required
                    && targetTerrainCompatible;

            boolean dominantTerrainCompatible = true;
            if (profile == Profile.OVERWORLD && dominant != null) {
                int dominantTerrainMatches = 0;
                for (int y : terrainY) {
                    if (terrainMatches(dominant, y)) {
                        dominantTerrainMatches++;
                    }
                }
                dominantTerrainCompatible = terrainMatches(dominant, centerTerrainY)
                        && dominantTerrainMatches >= required;
            }

            boolean qualifiedForDominant = dominant != null
                    && dominant.equals(centerBiome)
                    && dominantCount >= required
                    && dominantTerrainCompatible;
            int targetScore = targetMatches * 100
                    + targetTerrainMatches * 50
                    + (centerMatches ? 500 : 0)
                    + (centerTerrainMatches ? 250 : 0);

            return new Candidate(
                    seed,
                    centerBiome,
                    dominant,
                    targetMatches,
                    dominantCount,
                    targetTerrainMatches,
                    sampleCount,
                    centerTerrainY,
                    matchesTarget,
                    targetTerrainCompatible,
                    qualifiedForDominant,
                    targetScore);
        } catch (RuntimeException exception) {
            BiomePockets.LOGGER.debug(
                    "Could not climate/height-classify candidate pocket seed {} for {}",
                    seed,
                    targetId,
                    exception);
            return new Candidate(
                    seed,
                    null,
                    null,
                    0,
                    0,
                    0,
                    1,
                    Integer.MIN_VALUE,
                    false,
                    false,
                    false,
                    0);
        }
    }

    /**
     * Every probe is the dead center of a chunk. One probe therefore means exactly one
     * height lookup at the center of the center chunk. Five adds the four edge-center
     * chunks; nine additionally adds the four corners of the playable footprint.
     */
    private static int[][] probeChunkOffsets(int radius, int requestedCount) {
        int edge = Math.max(1, radius);
        if (requestedCount <= 1) {
            return new int[][] { { 0, 0 } };
        }
        if (requestedCount <= 5) {
            return new int[][] {
                    { 0, 0 },
                    { -edge, 0 },
                    { edge, 0 },
                    { 0, -edge },
                    { 0, edge }
            };
        }
        return new int[][] {
                { 0, 0 },
                { -edge, 0 },
                { edge, 0 },
                { 0, -edge },
                { 0, edge },
                { -edge, -edge },
                { -edge, edge },
                { edge, -edge },
                { edge, edge }
        };
    }

    private static boolean terrainMatches(ResourceLocation biomeId, int topSolidY) {
        TerrainClass terrainClass = terrainClass(biomeId);
        return switch (terrainClass) {
            case WATER -> topSolidY < NORMAL_LAND_MIN_Y;
            case MOUNTAIN -> topSolidY > NORMAL_LAND_MAX_Y;
            case NORMAL_LAND -> topSolidY >= NORMAL_LAND_MIN_Y
                    && topSolidY <= NORMAL_LAND_MAX_Y;
        };
    }

    private static TerrainClass terrainClass(ResourceLocation biomeId) {
        ResourceKey<Biome> key = ResourceKey.create(Registry.BIOME_REGISTRY, biomeId);
        if (BiomeDictionary.hasType(key, BiomeDictionary.Type.WATER)
                || BiomeDictionary.hasType(key, BiomeDictionary.Type.OCEAN)
                || BiomeDictionary.hasType(key, BiomeDictionary.Type.RIVER)) {
            return TerrainClass.WATER;
        }
        if (BiomeDictionary.hasType(key, BiomeDictionary.Type.MOUNTAIN)) {
            return TerrainClass.MOUNTAIN;
        }

        String path = biomeId.getPath();
        if (path.contains("ocean") || path.contains("river")) {
            return TerrainClass.WATER;
        }
        if (path.contains("mountain") || path.contains("peak")) {
            return TerrainClass.MOUNTAIN;
        }
        return TerrainClass.NORMAL_LAND;
    }

    private static void enqueueRejected(
            Profile profile,
            int radius,
            List<Candidate> rejected,
            long usedSeed) {
        for (Candidate candidate : rejected) {
            if (candidate.seed() == usedSeed
                    || !candidate.qualifiedForDominant()
                    || candidate.dominantBiome() == null) {
                continue;
            }
            enqueue(new BucketKey(profile, radius, candidate.dominantBiome()), candidate.seed());
        }
    }

    private static void enqueue(BucketKey key, long seed) {
        BUCKETS.computeIfAbsent(key, ignored -> new ArrayDeque<>()).addLast(seed);
    }

    private static void pruneEmptyBucket(BucketKey key) {
        Deque<Long> queue = BUCKETS.get(key);
        if (queue != null && queue.isEmpty()) {
            BUCKETS.remove(key);
        }
    }

    private static ResourceLocation biomeId(MinecraftServer server, Holder<Biome> biome) {
        return biome.unwrapKey()
                .map(ResourceKey::location)
                .orElseGet(() -> server.registryAccess()
                        .registryOrThrow(Registry.BIOME_REGISTRY)
                        .getKey(biome.value()));
    }

    @SuppressWarnings({"deprecation", "removal"})
    private static Profile profileFor(Holder<Biome> biome) {
        boolean nether = biome.is(BiomeTags.IS_NETHER);
        boolean end = biome.is(Tags.Biomes.IS_END);
        if (biome.unwrapKey().isPresent()) {
            ResourceKey<Biome> key = biome.unwrapKey().get();
            nether = nether || BiomeDictionary.hasType(key, BiomeDictionary.Type.NETHER);
            end = end || BiomeDictionary.hasType(key, BiomeDictionary.Type.END);
        }
        if (end) {
            return Profile.END;
        }
        if (nether) {
            return Profile.NETHER;
        }
        return Profile.OVERWORLD;
    }

    private static ServerLevel referenceLevel(MinecraftServer server, Profile profile) {
        return switch (profile) {
            case OVERWORLD -> server.overworld();
            case NETHER -> server.getLevel(Level.NETHER);
            case END -> server.getLevel(Level.END);
        };
    }

    private static long randomSeed() {
        UUID uuid = UUID.randomUUID();
        return uuid.getMostSignificantBits() ^ Long.rotateLeft(uuid.getLeastSignificantBits(), 23);
    }

    private static void ensureLoaded(MinecraftServer server) {
        Path path = cachePath(server);
        if (path.equals(loadedCachePath)) {
            return;
        }

        BUCKETS.clear();
        loadedCachePath = path;
        if (!Files.isRegularFile(path)) {
            return;
        }

        int loaded = 0;
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split("\\t");
                if (parts.length != 4) {
                    continue;
                }
                try {
                    Profile profile = Profile.valueOf(parts[0]);
                    int radius = Integer.parseInt(parts[1]);
                    ResourceLocation biome = new ResourceLocation(parts[2]);
                    long seed = Long.parseLong(parts[3]);
                    enqueue(new BucketKey(profile, radius, biome), seed);
                    loaded++;
                } catch (RuntimeException ignored) {
                    // Ignore malformed/stale cache rows without affecting the world.
                }
            }
        } catch (IOException exception) {
            BiomePockets.LOGGER.warn("Could not read pocket seed cache {}", path, exception);
        }

        if (loaded > 0) {
            BiomePockets.LOGGER.info(
                    "Loaded {} cached pocket seed candidate(s) from {}",
                    loaded,
                    path);
        }
    }

    private static void save(MinecraftServer server) {
        Path path = cachePath(server);
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        List<String> lines = new ArrayList<>();
        lines.add("# BiomePockets FIFO seed cache v3: profile<TAB>radius<TAB>biome<TAB>seed");
        for (Map.Entry<BucketKey, Deque<Long>> entry : BUCKETS.entrySet()) {
            BucketKey key = entry.getKey();
            for (Long seed : entry.getValue()) {
                lines.add(key.profile() + "\t" + key.radius() + "\t" + key.biome() + "\t" + seed);
            }
        }

        try {
            Files.createDirectories(path.getParent());
            Files.write(
                    temp,
                    lines,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            try {
                Files.move(
                        temp,
                        path,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            BiomePockets.LOGGER.warn("Could not persist pocket seed cache {}", path, exception);
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // Best effort only.
            }
        }
    }

    private static Path cachePath(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT)
                .resolve("data")
                .resolve(CACHE_FILE)
                .toAbsolutePath()
                .normalize();
    }

    private enum Profile {
        OVERWORLD,
        NETHER,
        END
    }

    private enum TerrainClass {
        WATER,
        NORMAL_LAND,
        MOUNTAIN
    }

    private record BucketKey(Profile profile, int radius, ResourceLocation biome) { }

    private record Candidate(
            long seed,
            ResourceLocation centerBiome,
            ResourceLocation dominantBiome,
            int targetMatches,
            int dominantMatches,
            int targetTerrainMatches,
            int sampleCount,
            int centerTerrainY,
            boolean matchesTarget,
            boolean targetTerrainCompatible,
            boolean qualifiedForDominant,
            int targetScore) { }
}
