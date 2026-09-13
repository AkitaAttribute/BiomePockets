package com.akitaattribute.biomepockets.client;

import com.akitaattribute.biomepockets.network.OpenPocketManagerPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

public final class ClientHooks {
    private ClientHooks() { }

    public static void openBiomeSelector(List<ResourceLocation> biomes) {
        Minecraft.getInstance().setScreen(new BiomeSelectorScreen(biomes));
    }

    public static void openPocketManager(OpenPocketManagerPacket state) {
        Minecraft.getInstance().setScreen(new PocketBiomeManagerScreen(state));
    }
}
