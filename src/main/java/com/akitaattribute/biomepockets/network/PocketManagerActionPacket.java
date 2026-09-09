package com.akitaattribute.biomepockets.network;

import com.akitaattribute.biomepockets.world.PocketClaimManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record PocketManagerActionPacket(PocketClaimManager.Action action) {
    public static void encode(PocketManagerActionPacket message, FriendlyByteBuf buffer) {
        buffer.writeEnum(message.action);
    }

    public static PocketManagerActionPacket decode(FriendlyByteBuf buffer) {
        return new PocketManagerActionPacket(buffer.readEnum(PocketClaimManager.Action.class));
    }

    public static void handle(PocketManagerActionPacket message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer sender = context.getSender();
        if (sender != null) {
            context.enqueueWork(() -> PocketClaimManager.handleAction(sender, message.action));
        }
        context.setPacketHandled(true);
    }
}
