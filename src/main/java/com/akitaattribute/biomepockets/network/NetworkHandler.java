package com.akitaattribute.biomepockets.network;

import com.akitaattribute.biomepockets.BiomePockets;
import com.akitaattribute.biomepockets.world.PocketClaimManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.List;

public final class NetworkHandler {
    private static final String PROTOCOL = "2";
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
        CHANNEL.registerMessage(packetId++, OpenPocketManagerPacket.class,
                OpenPocketManagerPacket::encode, OpenPocketManagerPacket::decode, OpenPocketManagerPacket::handle);
        CHANNEL.registerMessage(packetId++, PocketManagerActionPacket.class,
                PocketManagerActionPacket::encode, PocketManagerActionPacket::decode, PocketManagerActionPacket::handle);
    }

    public static void openSelector(ServerPlayer player, List<ResourceLocation> biomes) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new OpenBiomeSelectorPacket(biomes));
    }

    public static void openPocketManager(ServerPlayer player) {
        CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                OpenPocketManagerPacket.from(PocketClaimManager.stateFor(player)));
    }

    public static void sendPocketManagerAction(PocketClaimManager.Action action) {
        CHANNEL.sendToServer(new PocketManagerActionPacket(action));
    }
}
