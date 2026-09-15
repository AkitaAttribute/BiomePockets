package com.akitaattribute.biomepockets.network;

import com.akitaattribute.biomepockets.BiomePockets;
import com.akitaattribute.biomepockets.world.PocketClaimManager;
import com.akitaattribute.biomepockets.world.PocketDebugSettings;
import com.akitaattribute.biomepockets.world.PocketFriendlyExpansionManager;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.List;

public final class NetworkHandler {
    private static final String PROTOCOL = "6";
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
        CHANNEL.registerMessage(packetId++, UpdateDebugSettingsPacket.class,
                UpdateDebugSettingsPacket::encode,
                UpdateDebugSettingsPacket::decode,
                UpdateDebugSettingsPacket::handle);
        CHANNEL.registerMessage(packetId++, OpenPocketManagerPacket.class,
                OpenPocketManagerPacket::encode, OpenPocketManagerPacket::decode, OpenPocketManagerPacket::handle);
        CHANNEL.registerMessage(packetId++, PocketManagerActionPacket.class,
                PocketManagerActionPacket::encode, PocketManagerActionPacket::decode, PocketManagerActionPacket::handle);
        CHANNEL.registerMessage(packetId++, PocketGenerationCooldownPacket.class,
                PocketGenerationCooldownPacket::encode,
                PocketGenerationCooldownPacket::decode,
                PocketGenerationCooldownPacket::handle);
        CHANNEL.registerMessage(packetId++, PocketExpansionProgressPacket.class,
                PocketExpansionProgressPacket::encode,
                PocketExpansionProgressPacket::decode,
                PocketExpansionProgressPacket::handle);
        CHANNEL.registerMessage(packetId++, PocketManagerStateUpdatePacket.class,
                PocketManagerStateUpdatePacket::encode,
                PocketManagerStateUpdatePacket::decode,
                PocketManagerStateUpdatePacket::handle);
    }

    public static void openSelector(ServerPlayer player, List<ResourceLocation> biomes) {
        CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new OpenBiomeSelectorPacket(
                        biomes,
                        PocketDebugSettings.heightProbeAxis(),
                        PocketDebugSettings.generationOpsPerTick(),
                        PocketDebugSettings.canEdit(player)));
    }

    public static void openPocketManager(ServerPlayer player) {
        CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                OpenPocketManagerPacket.from(PocketClaimManager.stateFor(player)));
        PocketFriendlyExpansionManager.syncProgress(player);
    }

    /** Refreshes the manager only if the client still has it open. */
    public static void updatePocketManager(ServerPlayer player) {
        CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new PocketManagerStateUpdatePacket(
                        OpenPocketManagerPacket.from(PocketClaimManager.stateFor(player))));
        PocketFriendlyExpansionManager.syncProgress(player);
    }

    public static void sendPocketManagerAction(PocketClaimManager.Action action) {
        CHANNEL.sendToServer(new PocketManagerActionPacket(action));
    }

    public static void sendGenerationCooldown(
            ServerPlayer player,
            Item item,
            int completed,
            int total,
            boolean active) {
        ResourceLocation itemId = Registry.ITEM.getKey(item);
        if (itemId == null) {
            return;
        }
        CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new PocketGenerationCooldownPacket(itemId, completed, total, active));
    }

    public static void sendExpansionProgress(
            ServerPlayer player,
            int completed,
            int total,
            boolean active) {
        CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new PocketExpansionProgressPacket(completed, total, active));
    }
}
