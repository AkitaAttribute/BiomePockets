package com.akitaattribute.biomepockets.network;

import com.akitaattribute.biomepockets.client.ClientHooks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Server-to-client progress for a claimed-pocket expansion. */
public record PocketExpansionProgressPacket(int completed, int total, boolean active) {
    public static void encode(PocketExpansionProgressPacket message, FriendlyByteBuf buffer) {
        buffer.writeVarInt(Math.max(0, message.completed));
        buffer.writeVarInt(Math.max(0, message.total));
        buffer.writeBoolean(message.active);
    }

    public static PocketExpansionProgressPacket decode(FriendlyByteBuf buffer) {
        return new PocketExpansionProgressPacket(
                buffer.readVarInt(),
                buffer.readVarInt(),
                buffer.readBoolean());
    }

    public static void handle(
            PocketExpansionProgressPacket message,
            Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT,
                () -> () -> ClientHooks.updatePocketExpansionProgress(
                        message.completed,
                        message.total,
                        message.active)));
        context.setPacketHandled(true);
    }
}
