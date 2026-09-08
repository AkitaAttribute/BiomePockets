package com.akitaattribute.biomepockets.client;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

public final class ClientHooks {
    private ClientHooks() { }

    public static void openBiomeSelector(List<ResourceLocation> biomes) {
        Minecraft.getInstance().setScreen(new BiomeSelectorScreen(biomes));
    }
}
