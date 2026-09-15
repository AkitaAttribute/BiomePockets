package com.akitaattribute.biomepockets.client;

import com.akitaattribute.biomepockets.network.OpenPocketManagerPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

public final class ClientHooks {
    private ClientHooks() { }

    public static void openBiomeSelector(
            List<ResourceLocation> biomes,
            int heightProbeAxis,
            int generationOpsPerTick,
            boolean debugEditable) {
        Minecraft.getInstance().setScreen(new BiomeSelectorScreen(
                biomes,
                heightProbeAxis,
                generationOpsPerTick,
                debugEditable));
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
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof PocketBiomeManagerScreen) {
            minecraft.setScreen(new PocketBiomeManagerScreen(state));
        }
    }
}
