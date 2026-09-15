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
 * Selects random pocket seeds whose native climate and terrain are appropriate for the
 * requested biome before BiomePockets locks the resulting pocket to that biome.
 *
 * Failed candidates are classified by the biome they naturally favor and are persisted
 * in FIFO buckets. A cached seed is removed from the head when considered and is always
 * revalidated before it can be used. Seeds classified with an older validation format
 * are intentionally ignored by using a versioned cache filename.
 *
 * Candidate validation is deliberately separate from real chunk generation. Several
 * independent seeds can therefore be sampled concurrently without submitting multiple
 * chunks to Minecraft's worldgen pipeline. Search parallelism is capped so pocket seed
 * selection cannot consume every available processor on a busy server.
 */
public final class PocketSeedBroker {
    private static final String CACHE_FILE = "biomepockets-seed-cache-v2.tsv";
    private static final int MAX_RANDOM_ATTEMPTS = 96;
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

        // Cached seeds retain strict FIFO semantics. The head is always removed and
        // revalidated before a later cached seed may be considered.
        Deque<Long> cached = BUCKETS.get(targetBucket);
        if (cached != null) {
            while (!cached.isEmpty()) {
                long seed = cached.removeFirst();
                Candidate candidate = classify(referenceLevel, profile, targetId, radius, seed);
                if (candidate.matchesTarget()) {
                    pruneEmptyBucket(targetBucket);
                    save(server);
                    BiomePockets.LOGGER.info(
                            "Pocket seed broker consumed FIFO cached seed {} for {} {}x{}; biome={}/{}, terrain={}/{}",
                            seed,
                            targetId,
                            radius * 2 + 1,
                            radius * 2 + 1,
                            candidate.targetMatches(),
                            candidate.sampleCount(),
                            candidate.targetTerrainMatches(),
                            candidate.sampleCount());
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
                        "Pocket seed broker matched {} {}x{} at logical candidate {} ({} candidate(s) evaluated in parallel batches of up to {}): seed={}, biome={}/{}, terrain={}/{}",
                        targetId,
                        radius * 2 + 1,
                        radius * 2 + 1,
                        batchStart + selectedIndex + 1,
                        evaluated,
                        SEARCH_PARALLELISM,
                        selected.seed(),
                        selected.targetMatches(),
                        selected.sampleCount(),
                        selected.targetTerrainMatches(),
                        selected.sampleCount());
                return selected.seed();
            }
        }

        /*
         * Never deliberately fall back to an ocean basin for a land biome (or dry land
         * for an aquatic biome). If climate matching is unusually difficult, use the
         * best candidate that still satisfies the requested terrain character.
         */
        if (bestTerrainCompatible != null) {
            long selected = bestTerrainCompatible.seed();
            enqueueRejected(profile, radius, rejected, selected);
            save(server);
            BiomePockets.LOGGER.warn(
                    "Pocket seed broker did not find a full {} climate match in {} candidates; using terrain-safe seed {} (biome={}/{}, terrain={}/{})",
                    targetId,
                    MAX_RANDOM_ATTEMPTS,
                    selected,
                    bestTerrainCompatible.targetMatches(),
                    bestTerrainCompatible.sampleCount(),
                    bestTerrainCompatible.targetTerrainMatches(),
                    bestTerrainCompatible.sampleCount());
            return selected;
        }

        /*
         * Reaching this point means even terrain compatibility was unusually difficult.
         * Continue with the same bounded parallel sampler until we find terrain that is
         * at least safe for the requested land/water character.
         */
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
                        "Pocket seed broker required terrain-only fallback for {} at logical additional candidate {} ({} evaluated): seed={}",
                        targetId,
                        batchStart + selectedIndex + 1,
                        fallbackEvaluated,
                        selected.seed());
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
     * Evaluate independent random seeds concurrently. Results are returned in creation
     * order, not completion order, so the logical candidate ordering and FIFO bucketing
     * remain stable even though the expensive noise probes run in parallel.
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
            int probeAxis = PocketDebugSettings.effectiveHeightProbeAxis(radius);
            int[] blockOffsets = sampleBlockOffsets(radius, probeAxis);
            int sampleCount = blockOffsets.length * blockOffsets.length;
            int required = sampleCount / 2 + 1;
            boolean targetWantsWater = profile == Profile.OVERWORLD && isWaterBiome(targetId);

            Map<ResourceLocation, Integer> counts = new LinkedHashMap<>();
            boolean[] submergedSamples = profile == Profile.OVERWORLD
                    ? new boolean[sampleCount]
                    : null;
            ResourceLocation centerBiome = null;
            boolean centerTerrainMatches = profile != Profile.OVERWORLD;
            boolean centerSubmerged = false;
            int targetMatches = 0;
            int targetTerrainMatches = 0;
            int sampleIndex = 0;

            int seaLevel = generator.getSeaLevel();
            for (int blockX : blockOffsets) {
                for (int blockZ : blockOffsets) {
                    /*
                     * One solid-surface height is enough. On the Overworld,
                     * max(oceanFloor, seaLevel) is also the correct altitude at which to
                     * ask for the surface biome: sea level over water, terrain height on
                     * dry land. Nether/End use their ordinary world-surface height.
                     */
                    int terrainY;
                    int biomeY;
                    boolean submerged = false;
                    if (profile == Profile.OVERWORLD) {
                        terrainY = generator.getBaseHeight(
                                blockX,
                                blockZ,
                                Heightmap.Types.OCEAN_FLOOR_WG,
                                referenceLevel);
                        submerged = terrainY < seaLevel;
                        submergedSamples[sampleIndex] = submerged;
                        biomeY = Math.max(terrainY, seaLevel);
                    } else {
                        terrainY = generator.getBaseHeight(
                                blockX,
                                blockZ,
                                Heightmap.Types.WORLD_SURFACE_WG,
                                referenceLevel);
                        biomeY = terrainY;
                    }

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

                    boolean terrainMatches;
                    if (profile != Profile.OVERWORLD) {
                        terrainMatches = true;
                    } else {
                        terrainMatches = targetWantsWater ? submerged : !submerged;
                    }
                    if (terrainMatches) {
                        targetTerrainMatches++;
                    }

                    // Chunk 0's center is block 8,8. All odd probe grids include it.
                    if (blockX == 8 && blockZ == 8) {
                        centerBiome = naturalId;
                        centerTerrainMatches = terrainMatches;
                        centerSubmerged = submerged;
                    }
                    sampleIndex++;
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
            boolean targetTerrainCompatible = centerTerrainMatches && targetTerrainMatches >= required;
            boolean matchesTarget = centerMatches
                    && targetMatches >= required
                    && targetTerrainCompatible;

            boolean dominantTerrainCompatible = true;
            if (profile == Profile.OVERWORLD && dominant != null) {
                boolean dominantWantsWater = isWaterBiome(dominant);
                int dominantTerrainMatches = 0;
                for (boolean submerged : submergedSamples) {
                    if (dominantWantsWater ? submerged : !submerged) {
                        dominantTerrainMatches++;
                    }
                }
                boolean dominantCenterTerrainMatches = dominantWantsWater
                        ? centerSubmerged
                        : !centerSubmerged;
                dominantTerrainCompatible = dominantCenterTerrainMatches
                        && dominantTerrainMatches >= required;
            }

            boolean qualifiedForDominant = dominant != null
                    && dominant.equals(centerBiome)
                    && dominantCount >= required
                    && dominantTerrainCompatible;
            int targetScore = targetMatches * 100
                    + targetTerrainMatches * 10
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
                    matchesTarget,
                    targetTerrainCompatible,
                    qualifiedForDominant,
                    targetScore);
        } catch (RuntimeException exception) {
            BiomePockets.LOGGER.debug(
                    "Could not climate/terrain-classify candidate pocket seed {} for {}",
                    seed,
                    targetId,
                    exception);
            return new Candidate(seed, null, null, 0, 0, 0, 1, false, false, false, 0);
        }
    }

    /**
     * Returns an odd square probe grid distributed across the real starting footprint.
     * The coordinates are centered on block 8,8 (the center of chunk 0). AUTO is
     * resolved by PocketDebugSettings before this method: 3x3 for a 3x3 pocket and 5x5
     * for larger pockets. Explicit debug values can request 1x1 through 9x9 probes.
     */
    private static int[] sampleBlockOffsets(int radius, int requestedAxis) {
        int axis = Math.max(1, Math.min(9, requestedAxis));
        if ((axis & 1) == 0) {
            axis = Math.min(9, axis + 1);
        }
        if (axis == 1) {
            return new int[] { 8 };
        }

        int halfSpan = Math.max(1, radius) * 16;
        int[] result = new int[axis];
        for (int index = 0; index < axis; index++) {
            double fraction = index / (double) (axis - 1);
            result[index] = 8 + (int) Math.round(-halfSpan + (2.0D * halfSpan * fraction));
        }
        return result;
    }

    private static boolean isWaterBiome(ResourceLocation biomeId) {
        ResourceKey<Biome> key = ResourceKey.create(Registry.BIOME_REGISTRY, biomeId);
        if (BiomeDictionary.hasType(key, BiomeDictionary.Type.WATER)
                || BiomeDictionary.hasType(key, BiomeDictionary.Type.OCEAN)
                || BiomeDictionary.hasType(key, BiomeDictionary.Type.RIVER)) {
            return true;
        }

        String path = biomeId.getPath();
        return path.contains("ocean") || path.contains("river");
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
            BiomePockets.LOGGER.info("Loaded {} cached pocket seed candidate(s) from {}", loaded, path);
        }
    }

    private static void save(MinecraftServer server) {
        Path path = cachePath(server);
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        List<String> lines = new ArrayList<>();
        lines.add("# BiomePockets FIFO seed cache v2: profile<TAB>radius<TAB>biome<TAB>seed");
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

    private record BucketKey(Profile profile, int radius, ResourceLocation biome) { }

    private record Candidate(
            long seed,
            ResourceLocation centerBiome,
            ResourceLocation dominantBiome,
            int targetMatches,
            int dominantMatches,
            int targetTerrainMatches,
            int sampleCount,
            boolean matchesTarget,
            boolean targetTerrainCompatible,
            boolean qualifiedForDominant,
            int targetScore) { }
}
