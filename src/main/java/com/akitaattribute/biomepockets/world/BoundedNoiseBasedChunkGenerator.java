package com.akitaattribute.biomepockets.world;

import com.akitaattribute.biomepockets.BiomePockets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureFeatureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Noise generator that produces real worldgen only in the configured pocket square and
 * surrounds it with one complete chunk of solid barrier blocks on every horizontal
 * side. Chunks outside that prepared square remain void.
 *
 * Pocket biome population intentionally bypasses ChunkGenerator's normal multi-biome
 * feature-selection/indexing layer. A pocket already has one exact selected biome, so
 * the FEATURES stage executes that biome's BiomeGenerationSettings directly while
 * retaining each PlacedFeature's normal placement modifiers and biome checks.
 */
public final class BoundedNoiseBasedChunkGenerator extends NoiseBasedChunkGenerator {
    private final Holder<Biome> pocketBiome;
    private final long pocketSeed;

    /*
     * Claimed pockets expand in place. The existing dimension keeps its identity while
     * a staging dimension generates the newly unlocked ring. Once that ring has been
     * copied into the claimed dimension these bounds are advanced atomically on the
     * server thread so future containment checks reflect the new footprint.
     */
    private volatile int minPocketChunk;
    private volatile int maxPocketChunk;
    private volatile int minBarrierChunk;
    private volatile int maxBarrierChunk;
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
        setPocketBounds(minPocketChunk, maxPocketChunk);
    }

    public int getPocketRadius() {
        return Math.max(Math.abs(minPocketChunk), Math.abs(maxPocketChunk));
    }

    public long getPocketSeed() {
        return pocketSeed;
    }

    /**
     * Advance the playable square without regenerating the existing dimension. This is
     * called only after the staging terrain and the replacement barrier ring have been
     * copied successfully into the claimed level.
     */
    public void resizePocket(int radius) {
        int safeRadius = Math.max(1, radius);
        setPocketBounds(-safeRadius, safeRadius);
    }

    private void setPocketBounds(int minPocketChunk, int maxPocketChunk) {
        if (minPocketChunk > maxPocketChunk) {
            throw new IllegalArgumentException("Pocket minimum chunk cannot exceed maximum chunk");
        }
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

    public boolean isBarrierChunk(ChunkAccess chunk) {
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
     * Barrier chunks are uniform by definition. The old implementation called
     * ChunkAccess#setBlockState for every block from world bottom to world top, which
     * meant roughly 1.5 million individual block writes for the 16 barrier chunks
     * around even a 3x3 pocket. Replace whole section palettes instead. A single-value
     * PalettedContainer represents all 4096 blocks in a section as BARRIER without
     * touching them one by one.
     */
    private void fillBarrierChunk(ChunkAccess chunk) {
        LevelChunkSection[] sections = chunk.getSections();
        BlockState barrier = Blocks.BARRIER.defaultBlockState();

        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            LevelChunkSection oldSection = sections[sectionIndex];
            PalettedContainer<BlockState> states = new PalettedContainer<>(
                    Block.BLOCK_STATE_REGISTRY,
                    barrier,
                    PalettedContainer.Strategy.SECTION_STATES);
            LevelChunkSection replacement = new LevelChunkSection(
                    oldSection.bottomBlockY(),
                    states,
                    oldSection.getBiomes().copy());
            replacement.recalcBlockCounts();
            sections[sectionIndex] = replacement;
        }

        // Barrier chunks should never retain a block entity written by a feature or an
        // earlier interrupted generation attempt.
        for (BlockPos pos : new ArrayList<>(chunk.getBlockEntitiesPos())) {
            chunk.removeBlockEntity(pos);
        }
        chunk.setUnsaved(true);
    }

    /**
     * FEATURES has a one-chunk write radius, so any feature escaping the playable area
     * can only damage the surrounding barrier ring. Some features (notably lakes and
     * End islands) write directly and can replace barrier blocks. Repairing the ring is
     * also section-based: count changed states from the palette, then replace the whole
     * damaged section with one uniform barrier palette.
     */
    public int repairBarrierChunk(ChunkAccess chunk) {
        if (!isBarrierChunk(chunk)) {
            return 0;
        }

        BlockState barrier = Blocks.BARRIER.defaultBlockState();
        LevelChunkSection[] sections = chunk.getSections();
        int repaired = 0;

        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            LevelChunkSection section = sections[sectionIndex];
            if (!section.maybeHas(state -> !state.is(Blocks.BARRIER))) {
                continue;
            }

            final int[] changedInSection = { 0 };
            section.getStates().count((state, count) -> {
                if (!state.is(Blocks.BARRIER)) {
                    changedInSection[0] += count;
                }
            });
            repaired += changedInSection[0];

            PalettedContainer<BlockState> states = new PalettedContainer<>(
                    Block.BLOCK_STATE_REGISTRY,
                    barrier,
                    PalettedContainer.Strategy.SECTION_STATES);
            LevelChunkSection replacement = new LevelChunkSection(
                    section.bottomBlockY(),
                    states,
                    section.getBiomes().copy());
            replacement.recalcBlockCounts();
            sections[sectionIndex] = replacement;
        }

        if (repaired > 0) {
            for (BlockPos pos : new ArrayList<>(chunk.getBlockEntitiesPos())) {
                chunk.removeBlockEntity(pos);
            }
            chunk.setUnsaved(true);
        }
        return repaired;
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
        BlockPos origin = new BlockPos(chunkPos.getMinBlockX(), 0, chunkPos.getMinBlockZ());

        WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
        long decorationSeed = random.setDecorationSeed(pocketSeed, origin.getX(), origin.getZ());

        int attempted = 0;
        int placed = 0;
        int suppressed = 0;
        for (int step = 0; step < featureSteps.size(); step++) {
            int featureIndex = 0;
            for (Holder<PlacedFeature> feature : featureSteps.get(step)) {
                random.setFeatureSeed(decorationSeed, featureIndex, step);
                if (isVanillaEndBossFeature(feature)) {
                    suppressed++;
                    featureIndex++;
                    continue;
                }
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
                    "Pocket center chunk attempted {} placed features; {} reported placement; {} End boss features suppressed",
                    attempted,
                    placed,
                    suppressed);
        } else {
            BiomePockets.LOGGER.debug(
                    "Pocket chunk {},{} attempted {} placed features; {} reported placement; {} End boss features suppressed",
                    chunkPos.x,
                    chunkPos.z,
                    attempted,
                    placed,
                    suppressed);
        }
    }

    /**
     * Remove only vines occupying the playable perimeter whose outward support block
     * is the barrier ring. Run after barrier repair so a feature that temporarily
     * replaced the support block cannot hide one of these wall vines from cleanup.
     */
    public int removeBarrierSupportedVines(WorldGenLevel level) {
        int minBlock = minPocketChunk * 16;
        int maxBlock = ((maxPocketChunk + 1) * 16) - 1;
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight();
        int removed = 0;

        for (int y = minY; y < maxY; y++) {
            for (int z = minBlock; z <= maxBlock; z++) {
                removed += removeBarrierSupportedVine(level, minBlock, y, z, Direction.WEST);
                removed += removeBarrierSupportedVine(level, maxBlock, y, z, Direction.EAST);
            }
            for (int x = minBlock + 1; x < maxBlock; x++) {
                removed += removeBarrierSupportedVine(level, x, y, minBlock, Direction.NORTH);
                removed += removeBarrierSupportedVine(level, x, y, maxBlock, Direction.SOUTH);
            }
        }
        return removed;
    }

    private static int removeBarrierSupportedVine(
            WorldGenLevel level,
            int x,
            int y,
            int z,
            Direction outward) {
        BlockPos pos = new BlockPos(x, y, z);
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof VineBlock)) {
            return 0;
        }
        if (!level.getBlockState(pos.relative(outward)).is(Blocks.BARRIER)) {
            return 0;
        }

        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
        return 1;
    }

    private static boolean isVanillaEndBossFeature(Holder<PlacedFeature> feature) {
        return feature.unwrapKey().map(key -> {
            ResourceLocation id = key.location();
            if (!"minecraft".equals(id.getNamespace())) {
                return false;
            }
            String path = id.getPath();
            return "end_spike".equals(path)
                    || "end_podium".equals(path)
                    || "end_platform".equals(path);
        }).orElse(false);
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
