package com.akitaattribute.biomepockets.network;

import com.akitaattribute.biomepockets.client.ClientHooks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public record OpenBiomeSelectorPacket(
        List<ResourceLocation> biomes,
        int heightProbeAxis,
        int generationOpsPerTick,
        boolean debugEditable) {
    public OpenBiomeSelectorPacket {
        biomes = List.copyOf(biomes);
    }

    public static void encode(OpenBiomeSelectorPacket message, FriendlyByteBuf buffer) {
        buffer.writeVarInt(message.biomes.size());
        for (ResourceLocation biome : message.biomes) {
            buffer.writeResourceLocation(biome);
        }
        buffer.writeVarInt(message.heightProbeAxis);
        buffer.writeVarInt(message.generationOpsPerTick);
        buffer.writeBoolean(message.debugEditable);
    }

    public static OpenBiomeSelectorPacket decode(FriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        List<ResourceLocation> biomes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            biomes.add(buffer.readResourceLocation());
        }
        return new OpenBiomeSelectorPacket(
                biomes,
                buffer.readVarInt(),
                buffer.readVarInt(),
                buffer.readBoolean());
    }

    public static void handle(OpenBiomeSelectorPacket message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientHooks.openBiomeSelector(
                        message.biomes,
                        message.heightProbeAxis,
                        message.generationOpsPerTick,
                        message.debugEditable)));
        context.setPacketHandled(true);
    }
}
