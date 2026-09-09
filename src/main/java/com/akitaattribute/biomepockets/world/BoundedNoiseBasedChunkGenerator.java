package com.akitaattribute.biomepockets.world;

import com.akitaattribute.biomepockets.BiomePockets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureFeatureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureManager;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Noise generator that produces real worldgen only in the configured 3x3 pocket and
 * surrounds it with one complete chunk of solid barrier blocks on every horizontal
 * side. Chunks outside that 5x5 prepared square remain void.
 *
 * Pocket biome population intentionally bypasses ChunkGenerator's normal multi-biome
 * feature-selection/indexing layer. A pocket already has one exact selected biome, so
 * the FEATURES stage executes that biome's BiomeGenerationSettings directly while
 * retaining each PlacedFeature's normal placement modifiers and biome checks.
 */
public final class BoundedNoiseBasedChunkGenerator extends NoiseBasedChunkGenerator {
    private final Holder<Biome> pocketBiome;
    private final long pocketSeed;
    private final int minPocketChunk;
    private final int maxPocketChunk;
    private final int minBarrierChunk;
    private final int maxBarrierChunk;
    private boolean loggedFeaturePlan;

    public BoundedNoiseBasedChunkGenerator(
            Registry<StructureSet> structureSets,
            Registry<NormalNoise.NoiseParameters> noiseParameters,
            BiomeSource biomeSource,
            Holder<Biome> pocketBiome,
            long seed,
            Holder<NoiseGeneratorSettings> settings,
            int minPocketChunk,
            int maxPocketChunk) {
        super(structureSets, noiseParameters, biomeSource, seed, settings);
        this.pocketBiome = pocketBiome;
        this.pocketSeed = seed;
        this.minPocketChunk = minPocketChunk;
        this.maxPocketChunk = maxPocketChunk;
        this.minBarrierChunk = minPocketChunk - 1;
        this.maxBarrierChunk = maxPocketChunk + 1;
    }

    private boolean isPocketChunk(ChunkAccess chunk) {
        return isPocketChunk(chunk.getPos());
    }

    private boolean isPocketChunk(ChunkPos pos) {
        return pos.x >= minPocketChunk && pos.x <= maxPocketChunk
                && pos.z >= minPocketChunk && pos.z <= maxPocketChunk;
    }

    private boolean isBarrierChunk(ChunkAccess chunk) {
        ChunkPos pos = chunk.getPos();
        return !isPocketChunk(pos)
                && pos.x >= minBarrierChunk && pos.x <= maxBarrierChunk
                && pos.z >= minBarrierChunk && pos.z <= maxBarrierChunk;
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(
            Executor executor,
            Blender blender,
            StructureFeatureManager structureFeatureManager,
            ChunkAccess chunk) {
        if (isPocketChunk(chunk)) {
            return super.fillFromNoise(executor, blender, structureFeatureManager, chunk);
        }
        if (isBarrierChunk(chunk)) {
            fillBarrierChunk(chunk);
        }
        return CompletableFuture.completedFuture(chunk);
    }

    /**
     * Fills each of the 16 chunks surrounding the playable 3x3 area from build bottom
     * to build top. Doing this directly on ChunkAccess during worldgen is much cheaper
     * than issuing ~1.5 million live-world block updates, and it ensures neighboring
     * barrier blocks already exist when edge trees/features evaluate placement.
     */
    private void fillBarrierChunk(ChunkAccess chunk) {
        ChunkPos chunkPos = chunk.getPos();
        int minX = chunkPos.getMinBlockX();
        int maxX = chunkPos.getMaxBlockX();
        int minZ = chunkPos.getMinBlockZ();
        int maxZ = chunkPos.getMaxBlockZ();
        int minY = chunk.getMinBuildHeight();
        int maxY = chunk.getMaxBuildHeight();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int y = minY; y < maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    chunk.setBlockState(pos.set(x, y, z), Blocks.BARRIER.defaultBlockState(), false);
                }
            }
        }
        chunk.setUnsaved(true);
    }

    @Override
    public void buildSurface(
            WorldGenRegion region,
            StructureFeatureManager structureFeatureManager,
            ChunkAccess chunk) {
        if (isPocketChunk(chunk)) {
            super.buildSurface(region, structureFeatureManager, chunk);
        }
    }

    @Override
    public void applyCarvers(
            WorldGenRegion region,
            long seed,
            BiomeManager biomeManager,
            StructureFeatureManager structureFeatureManager,
            ChunkAccess chunk,
            GenerationStep.Carving carvingStep) {
        if (isPocketChunk(chunk)) {
            super.applyCarvers(region, seed, biomeManager, structureFeatureManager, chunk, carvingStep);
        }
    }

    @Override
    public void applyBiomeDecoration(
            WorldGenLevel level,
            ChunkAccess chunk,
            StructureFeatureManager structureFeatureManager) {
        if (!isPocketChunk(chunk)) {
            return;
        }

        BiomeGenerationSettings generationSettings = pocketBiome.value().getGenerationSettings();
        List<HolderSet<PlacedFeature>> featureSteps = generationSettings.features();

        if (!loggedFeaturePlan) {
            int totalFeatures = featureSteps.stream().mapToInt(HolderSet::size).sum();
            String biomeName = pocketBiome.unwrapKey()
                    .map(key -> key.location().toString())
                    .orElse("<direct-biome>");
            BiomePockets.LOGGER.info(
                    "Pocket biome {} exposes {} placed features across {} generation steps",
                    biomeName,
                    totalFeatures,
                    featureSteps.size());
            loggedFeaturePlan = true;
        }

        ChunkPos chunkPos = chunk.getPos();
        // Match vanilla ChunkPos#getWorldPosition(): decoration starts at the chunk's
        // minimum X/Z with Y=0, even in dimensions whose minimum build height is -64.
        BlockPos origin = new BlockPos(chunkPos.getMinBlockX(), 0, chunkPos.getMinBlockZ());

        WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
        long decorationSeed = random.setDecorationSeed(pocketSeed, origin.getX(), origin.getZ());

        int attempted = 0;
        int placed = 0;
        for (int step = 0; step < featureSteps.size(); step++) {
            int featureIndex = 0;
            for (Holder<PlacedFeature> feature : featureSteps.get(step)) {
                random.setFeatureSeed(decorationSeed, featureIndex, step);
                attempted++;
                if (feature.value().placeWithBiomeCheck(level, this, random, origin)) {
                    placed++;
                }
                featureIndex++;
            }
        }

        chunk.setUnsaved(true);

        if (chunkPos.x == 0 && chunkPos.z == 0) {
            BiomePockets.LOGGER.info(
                    "Pocket center chunk attempted {} placed features; {} reported placement",
                    attempted,
                    placed);
        } else {
            BiomePockets.LOGGER.debug(
                    "Pocket chunk {},{} attempted {} placed features; {} reported placement",
                    chunkPos.x,
                    chunkPos.z,
                    attempted,
                    placed);
        }
    }

    @Override
    public void createStructures(
            RegistryAccess registryAccess,
            StructureFeatureManager structureFeatureManager,
            ChunkAccess chunk,
            StructureManager structureManager,
            long seed) {
        if (isPocketChunk(chunk)) {
            super.createStructures(registryAccess, structureFeatureManager, chunk, structureManager, seed);
        }
    }

    @Override
    public void createReferences(
            WorldGenLevel level,
            StructureFeatureManager structureFeatureManager,
            ChunkAccess chunk) {
        if (isPocketChunk(chunk)) {
            super.createReferences(level, structureFeatureManager, chunk);
        }
    }
}
