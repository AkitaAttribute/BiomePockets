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
 * Overworld-style noise generator that only produces real worldgen in the configured
 * pocket chunk square. Minecraft may still request dependency/view-distance chunks,
 * but those chunks remain empty instead of producing additional terrain.
 *
 * Pocket biome population intentionally bypasses ChunkGenerator's normal multi-biome
 * feature-selection/indexing layer. A pocket already has one exact selected biome, so
 * the FEATURES stage executes that biome's BiomeGenerationSettings directly while
 * retaining each PlacedFeature's normal placement modifiers and biome checks.
 */
public final class BoundedNoiseBasedChunkGenerator extends NoiseBasedChunkGenerator {
    private final Holder<Biome> pocketBiome;
    private final int minPocketChunk;
    private final int maxPocketChunk;
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
        this.minPocketChunk = minPocketChunk;
        this.maxPocketChunk = maxPocketChunk;
    }

    private boolean isPocketChunk(ChunkAccess chunk) {
        int x = chunk.getPos().x;
        int z = chunk.getPos().z;
        return x >= minPocketChunk && x <= maxPocketChunk
                && z >= minPocketChunk && z <= maxPocketChunk;
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(
            Executor executor,
            Blender blender,
            StructureFeatureManager structureFeatureManager,
            ChunkAccess chunk) {
        if (!isPocketChunk(chunk)) {
            return CompletableFuture.completedFuture(chunk);
        }
        return super.fillFromNoise(executor, blender, structureFeatureManager, chunk);
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
        BlockPos origin = new BlockPos(
                chunkPos.getMinBlockX(),
                level.getMinBuildHeight(),
                chunkPos.getMinBlockZ());

        WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
        long decorationSeed = random.setDecorationSeed(level.getSeed(), origin.getX(), origin.getZ());

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

        // Build the physical pocket boundary in the worldgen worker as part of the
        // FEATURES pass. This avoids tens of thousands of ServerLevel#setBlock calls
        // on the main server thread before teleporting the player.
        buildBarrierForChunk(level, chunkPos);
        chunk.setUnsaved(true);

        BiomePockets.LOGGER.debug(
                "Pocket chunk {},{} attempted {} placed features; {} reported placement",
                chunkPos.x,
                chunkPos.z,
                attempted,
                placed);
    }

    private void buildBarrierForChunk(WorldGenLevel level, ChunkPos chunkPos) {
        int minBarrierBlock = minPocketChunk * 16 - 1;
        int maxBarrierBlock = (maxPocketChunk + 1) * 16;
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight();
        int minX = chunkPos.getMinBlockX();
        int maxX = chunkPos.getMaxBlockX();
        int minZ = chunkPos.getMinBlockZ();
        int maxZ = chunkPos.getMaxBlockZ();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int y = minY; y < maxY; y++) {
            if (chunkPos.x == minPocketChunk) {
                for (int z = minZ; z <= maxZ; z++) {
                    level.setBlock(pos.set(minBarrierBlock, y, z), Blocks.BARRIER.defaultBlockState(), 2);
                }
            }
            if (chunkPos.x == maxPocketChunk) {
                for (int z = minZ; z <= maxZ; z++) {
                    level.setBlock(pos.set(maxBarrierBlock, y, z), Blocks.BARRIER.defaultBlockState(), 2);
                }
            }
            if (chunkPos.z == minPocketChunk) {
                for (int x = minX; x <= maxX; x++) {
                    level.setBlock(pos.set(x, y, minBarrierBlock), Blocks.BARRIER.defaultBlockState(), 2);
                }
            }
            if (chunkPos.z == maxPocketChunk) {
                for (int x = minX; x <= maxX; x++) {
                    level.setBlock(pos.set(x, y, maxBarrierBlock), Blocks.BARRIER.defaultBlockState(), 2);
                }
            }

            if (chunkPos.x == minPocketChunk && chunkPos.z == minPocketChunk) {
                level.setBlock(pos.set(minBarrierBlock, y, minBarrierBlock), Blocks.BARRIER.defaultBlockState(), 2);
            }
            if (chunkPos.x == minPocketChunk && chunkPos.z == maxPocketChunk) {
                level.setBlock(pos.set(minBarrierBlock, y, maxBarrierBlock), Blocks.BARRIER.defaultBlockState(), 2);
            }
            if (chunkPos.x == maxPocketChunk && chunkPos.z == minPocketChunk) {
                level.setBlock(pos.set(maxBarrierBlock, y, minBarrierBlock), Blocks.BARRIER.defaultBlockState(), 2);
            }
            if (chunkPos.x == maxPocketChunk && chunkPos.z == maxPocketChunk) {
                level.setBlock(pos.set(maxBarrierBlock, y, maxBarrierBlock), Blocks.BARRIER.defaultBlockState(), 2);
            }
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
