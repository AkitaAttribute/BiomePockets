package com.akitaattribute.biomepockets.world;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.biome.Biome;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class BiomeCatalog {
    private BiomeCatalog() { }

    public static List<ResourceLocation> getBiomeIds(MinecraftServer server) {
        Registry<Biome> registry = server.registryAccess().registryOrThrow(Registry.BIOME_REGISTRY);
        return registry.keySet().stream()
                .sorted(Comparator.comparing(ResourceLocation::toString))
                .toList();
    }

    public static Optional<Holder<Biome>> getBiome(MinecraftServer server, ResourceLocation id) {
        Registry<Biome> registry = server.registryAccess().registryOrThrow(Registry.BIOME_REGISTRY);
        ResourceKey<Biome> key = ResourceKey.create(Registry.BIOME_REGISTRY, id);
        return registry.getHolder(key);
    }
}
