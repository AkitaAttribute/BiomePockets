package com.akitaattribute.biomepockets.network;

import com.akitaattribute.biomepockets.client.ClientGenerationCooldowns;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record PocketGenerationCooldownPacket(
        ResourceLocation itemId,
        int completed,
        int total,
        boolean active) {

    public static void encode(PocketGenerationCooldownPacket message, FriendlyByteBuf buffer) {
        buffer.writeResourceLocation(message.itemId);
        buffer.writeVarInt(Math.max(0, message.completed));
        buffer.writeVarInt(Math.max(0, message.total));
        buffer.writeBoolean(message.active);
    }

    public static PocketGenerationCooldownPacket decode(FriendlyByteBuf buffer) {
        return new PocketGenerationCooldownPacket(
                buffer.readResourceLocation(),
                buffer.readVarInt(),
                buffer.readVarInt(),
                buffer.readBoolean());
    }

    public static void handle(
            PocketGenerationCooldownPacket message,
            Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT,
                () -> () -> ClientGenerationCooldowns.update(
                        message.itemId,
                        message.completed,
                        message.total,
                        message.active)));
        context.setPacketHandled(true);
    }
}
