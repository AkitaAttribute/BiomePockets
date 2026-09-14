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

/**
 * Selects random pocket seeds whose native climate/terrain would naturally resolve to
 * the requested biome before BiomePockets locks the finished pocket to that biome.
 *
 * Rejected random seeds are not automatically wasted. If a rejected seed forms a
 * coherent region for some other naturally selected biome, it is placed into a FIFO
 * bucket for that biome/profile/starting-radius. A future request for that exact bucket
 * consumes the oldest seed and removes it permanently from the queue.
 */
public final class PocketSeedBroker {
    private static final String CACHE_FILE = "biomepockets-seed-cache-v1.tsv";
    private static final int MAX_RANDOM_ATTEMPTS = 96;
    private static final int REQUIRED_MATCHES = 5;
    private static final int SAMPLE_COUNT = 9;

    private static final Map<BucketKey, Deque<Long>> BUCKETS = new LinkedHashMap<>();
    private static Path loadedCachePath;

    private PocketSeedBroker() { }

    /**
     * Return a unique seed suitable for this biome and pocket radius when possible.
     * Cached seeds are consumed FIFO. Newly tested seeds remain random.
     */
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

        // FIFO means we always inspect and remove the head first. A stale cached seed
        // (for example after a modpack worldgen change) is reclassified rather than
        // silently trusted forever.
        Deque<Long> cached = BUCKETS.get(targetBucket);
        if (cached != null) {
            while (!cached.isEmpty()) {
                long seed = cached.removeFirst();
                Candidate candidate = classify(server, referenceLevel, profile, targetId, radius, seed);
                if (candidate.matchesTarget()) {
                    pruneEmptyBucket(targetBucket);
                    save(server);
                    BiomePockets.LOGGER.info(
                            "Pocket seed broker consumed FIFO cached seed {} for {} {}x{} ({} cached seed(s) remain)",
                            seed,
                            targetId,
                            radius * 2 + 1,
                            radius * 2 + 1,
                            BUCKETS.getOrDefault(targetBucket, new ArrayDeque<>()).size());
                    return seed;
                }

                // Worldgen changed since this seed was cached. Preserve it only if it
                // still cleanly belongs to some other biome bucket.
                if (candidate.qualifiedForDominant()
                        && candidate.dominantBiome() != null
                        && !candidate.dominantBiome().equals(targetId)) {
                    enqueue(new BucketKey(profile, radius, candidate.dominantBiome()), seed);
                }
            }
            pruneEmptyBucket(targetBucket);
        }

        List<Candidate> rejected = new ArrayList<>();
        Candidate best = null;
        for (int attempt = 1; attempt <= MAX_RANDOM_ATTEMPTS; attempt++) {
            long seed = randomSeed();
            Candidate candidate = classify(server, referenceLevel, profile, targetId, radius, seed);
            if (candidate.matchesTarget()) {
                enqueueRejected(profile, radius, rejected, seed);
                save(server);
                BiomePockets.LOGGER.info(
                        "Pocket seed broker matched {} {}x{} after {} random candidate(s): seed={} ({}/{})",
                        targetId,
                        radius * 2 + 1,
                        radius * 2 + 1,
                        attempt,
                        seed,
                        candidate.targetMatches(),
                        SAMPLE_COUNT);
                return seed;
            }

            rejected.add(candidate);
            if (best == null || candidate.targetScore() > best.targetScore()) {
                best = candidate;
            }
        }

        // If an exact coherent match was not found, use the candidate that came closest
        // instead of throwing away all of the information and selecting a fresh unknown
        // seed. Do not place that used seed into another bucket.
        long selected = best == null ? randomSeed() : best.seed();
        enqueueRejected(profile, radius, rejected, selected);
        save(server);

        if (best == null || best.targetMatches() == 0) {
            BiomePockets.LOGGER.warn(
                    "Pocket seed broker could not naturally resolve {} in {} candidates; using random/best-effort seed {}",
                    targetId,
                    MAX_RANDOM_ATTEMPTS,
                    selected);
        } else {
            BiomePockets.LOGGER.warn(
                    "Pocket seed broker did not find a full {} match in {} candidates; using best seed {} ({}/{} samples, center={})",
                    targetId,
                    MAX_RANDOM_ATTEMPTS,
                    selected,
                    best.targetMatches(),
                    SAMPLE_COUNT,
                    best.centerBiome());
        }
        return selected;
    }

    private static Candidate classify(
            MinecraftServer server,
            ServerLevel referenceLevel,
            Profile profile,
            ResourceLocation targetId,
            int radius,
            long seed) {
        try {
            ChunkGenerator candidateGenerator = referenceLevel.getChunkSource().getGenerator().withSeed(seed);
            Map<ResourceLocation, Integer> counts = new LinkedHashMap<>();
            ResourceLocation centerBiome = null;
            int targetMatches = 0;

            int[] chunkOffsets = new int[] { -radius, 0, radius };
            for (int chunkX : chunkOffsets) {
                for (int chunkZ : chunkOffsets) {
                    int blockX = chunkX * 16 + 8;
                    int blockZ = chunkZ * 16 + 8;
                    int surfaceY;
                    try {
                        surfaceY = candidateGenerator.getBaseHeight(
                                blockX,
                                blockZ,
                                Heightmap.Types.WORLD_SURFACE_WG,
                                referenceLevel);
                    } catch (RuntimeException ignored) {
                        surfaceY = candidateGenerator.getSeaLevel();
                    }
                    surfaceY = Math.max(
                            referenceLevel.getMinBuildHeight() + 1,
                            Math.min(referenceLevel.getMaxBuildHeight() - 1, surfaceY));

                    Holder<Biome> natural = candidateGenerator.getNoiseBiome(
                            QuartPos.fromBlock(blockX),
                            QuartPos.fromBlock(surfaceY),
                            QuartPos.fromBlock(blockZ));
                    ResourceLocation naturalId = biomeId(server, natural);
                    if (naturalId == null) {
                        continue;
                    }

                    if (chunkX == 0 && chunkZ == 0) {
                        centerBiome = naturalId;
                    }
                    counts.merge(naturalId, 1, Integer::sum);
                    if (naturalId.equals(targetId)) {
                        targetMatches++;
                    }
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
            boolean matchesTarget = centerMatches && targetMatches >= REQUIRED_MATCHES;
            boolean qualifiedForDominant = dominant != null
                    && dominant.equals(centerBiome)
                    && dominantCount >= REQUIRED_MATCHES;
            int targetScore = targetMatches * 10 + (centerMatches ? 20 : 0);
            return new Candidate(
                    seed,
                    profile,
                    centerBiome,
                    dominant,
                    targetMatches,
                    dominantCount,
                    matchesTarget,
                    qualifiedForDominant,
                    targetScore);
        } catch (RuntimeException exception) {
            BiomePockets.LOGGER.debug(
                    "Could not climate-classify candidate pocket seed {} for {}",
                    seed,
                    targetId,
                    exception);
            return new Candidate(seed, profile, null, null, 0, 0, false, false, 0);
        }
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
        lines.add("# BiomePockets FIFO seed cache v1: profile<TAB>radius<TAB>biome<TAB>seed");
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
            Profile profile,
            ResourceLocation centerBiome,
            ResourceLocation dominantBiome,
            int targetMatches,
            int dominantMatches,
            boolean matchesTarget,
            boolean qualifiedForDominant,
            int targetScore) { }
}
