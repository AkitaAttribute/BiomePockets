package com.akitaattribute.biomepockets.network;

import com.akitaattribute.biomepockets.world.BiomeCatalog;
import com.akitaattribute.biomepockets.world.PocketInitialSizeManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record SelectBiomePacket(ResourceLocation biome) {
    public static void encode(SelectBiomePacket message, FriendlyByteBuf buffer) {
        buffer.writeResourceLocation(message.biome);
    }

    public static SelectBiomePacket decode(FriendlyByteBuf buffer) {
        return new SelectBiomePacket(buffer.readResourceLocation());
    }

    public static void handle(SelectBiomePacket message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer sender = context.getSender();
        context.enqueueWork(() -> {
            if (sender == null) {
                return;
            }
            if (BiomeCatalog.getBiome(sender.getServer(), message.biome).isPresent()) {
                PocketInitialSizeManager.createAndTeleport(sender, message.biome);
            }
        });
        context.setPacketHandled(true);
    }
}
