package com.akitaattribute.biomepockets.world;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.StructureFeatureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureManager;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Overworld-style noise generator that only produces real worldgen in the configured
 * pocket chunk square. Minecraft may still request dependency/view-distance chunks,
 * but those chunks remain empty instead of producing additional terrain.
 *
 * Biome decoration is deliberately deferred from the normal FEATURES transition.
 * Dynamic pocket levels have proven to reach terrain/carvers/FULL without reliably
 * executing biome population, so PocketDimensionManager invokes exactly one explicit
 * vanilla decoration pass after all nine terrain chunks exist.
 */
public final class BoundedNoiseBasedChunkGenerator extends NoiseBasedChunkGenerator {
    private final int minPocketChunk;
    private final int maxPocketChunk;
    private final NoiseBasedChunkGenerator decorationGenerator;

    public BoundedNoiseBasedChunkGenerator(
            Registry<StructureSet> structureSets,
            Registry<NormalNoise.NoiseParameters> noiseParameters,
            BiomeSource biomeSource,
            long seed,
            Holder<NoiseGeneratorSettings> settings,
            int minPocketChunk,
            int maxPocketChunk) {
        super(structureSets, noiseParameters, biomeSource, seed, settings);
        this.decorationGenerator = new NoiseBasedChunkGenerator(
                structureSets,
                noiseParameters,
                biomeSource,
                seed,
                settings
        );
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
        // Intentionally deferred. See decoratePocketChunk(). This prevents a possible
        // normal FEATURES pass plus an explicit pass from decorating a chunk twice.
    }

    /**
     * Runs the standard 1.18.2 NoiseBasedChunkGenerator biome-population routine once
     * the complete 3x3 pocket terrain exists. This is vanilla's normal placed-feature
     * path for trees, grass, flowers, ores, springs, and other biome decoration.
     */
    public void decoratePocketChunk(
            WorldGenLevel level,
            ChunkAccess chunk,
            StructureFeatureManager structureFeatureManager) {
        if (isPocketChunk(chunk)) {
            decorationGenerator.applyBiomeDecoration(level, chunk, structureFeatureManager);
            chunk.setUnsaved(true);
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
