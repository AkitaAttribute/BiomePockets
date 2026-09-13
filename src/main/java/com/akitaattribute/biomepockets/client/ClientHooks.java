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

    public static void updatePocketExpansionProgress(int completed, int total, boolean active) {
        if (Minecraft.getInstance().screen instanceof PocketBiomeManagerScreen manager) {
            manager.updateExpansionProgress(completed, total, active);
        }
    }

    public static void updatePocketManager(OpenPocketManagerPacket state) {
        if (Minecraft.getInstance().screen instanceof PocketBiomeManagerScreen manager) {
            manager.updateState(state);
        }
    }
}
