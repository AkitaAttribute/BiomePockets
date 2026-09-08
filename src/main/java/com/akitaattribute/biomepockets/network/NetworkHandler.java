package com.akitaattribute.biomepockets.network;

import com.akitaattribute.biomepockets.BiomePockets;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.List;

public final class NetworkHandler {
    private static final String PROTOCOL = "1";
    private static int packetId = 0;

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(BiomePockets.MOD_ID, "main"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals
    );

    private NetworkHandler() { }

    public static void register() {
        CHANNEL.registerMessage(packetId++, OpenBiomeSelectorPacket.class,
                OpenBiomeSelectorPacket::encode, OpenBiomeSelectorPacket::decode, OpenBiomeSelectorPacket::handle);
        CHANNEL.registerMessage(packetId++, SelectBiomePacket.class,
                SelectBiomePacket::encode, SelectBiomePacket::decode, SelectBiomePacket::handle);
    }

    public static void openSelector(ServerPlayer player, List<ResourceLocation> biomes) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new OpenBiomeSelectorPacket(biomes));
    }
}
