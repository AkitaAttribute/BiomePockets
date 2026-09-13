package com.akitaattribute.biomepockets.network;

import com.akitaattribute.biomepockets.client.ClientHooks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Refreshes an already-open Pocket Biome Manager without opening it if the player
 * closed the screen while an expansion was running.
 */
public record PocketManagerStateUpdatePacket(OpenPocketManagerPacket state) {
    public static void encode(PocketManagerStateUpdatePacket message, FriendlyByteBuf buffer) {
        OpenPocketManagerPacket.encode(message.state, buffer);
    }

    public static PocketManagerStateUpdatePacket decode(FriendlyByteBuf buffer) {
        return new PocketManagerStateUpdatePacket(OpenPocketManagerPacket.decode(buffer));
    }

    public static void handle(
            PocketManagerStateUpdatePacket message,
            Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT,
                () -> () -> ClientHooks.updatePocketManager(message.state)));
        context.setPacketHandled(true);
    }
}
