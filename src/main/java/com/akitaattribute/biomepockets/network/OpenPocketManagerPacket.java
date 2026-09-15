package com.akitaattribute.biomepockets.network;

import com.akitaattribute.biomepockets.client.ClientHooks;
import com.akitaattribute.biomepockets.world.PocketClaimManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record OpenPocketManagerPacket(
        boolean hasClaim,
        boolean canClaim,
        boolean canUnclaim,
        boolean canExpand,
        boolean canVisit,
        boolean canExit,
        boolean expanding,
        boolean creative,
        int expansionCost,
        int availableXp,
        int currentSize,
        int nextSize,
        String claimedDimension) {

    public static OpenPocketManagerPacket from(PocketClaimManager.ManagerState state) {
        return new OpenPocketManagerPacket(
                state.hasClaim(),
                state.canClaim(),
                state.canUnclaim(),
                state.canExpand(),
                state.canVisit(),
                state.canExit(),
                state.expanding(),
                state.creative(),
                state.expansionCost(),
                state.availableXp(),
                state.currentSize(),
                state.nextSize(),
                state.claimedDimension());
    }

    public static void encode(OpenPocketManagerPacket message, FriendlyByteBuf buffer) {
        buffer.writeBoolean(message.hasClaim);
        buffer.writeBoolean(message.canClaim);
        buffer.writeBoolean(message.canUnclaim);
        buffer.writeBoolean(message.canExpand);
        buffer.writeBoolean(message.canVisit);
        buffer.writeBoolean(message.canExit);
        buffer.writeBoolean(message.expanding);
        buffer.writeBoolean(message.creative);
        buffer.writeVarInt(message.expansionCost);
        buffer.writeVarInt(message.availableXp);
        buffer.writeVarInt(message.currentSize);
        buffer.writeVarInt(message.nextSize);
        buffer.writeUtf(message.claimedDimension, 256);
    }

    public static OpenPocketManagerPacket decode(FriendlyByteBuf buffer) {
        return new OpenPocketManagerPacket(
                buffer.readBoolean(),
                buffer.readBoolean(),
                buffer.readBoolean(),
                buffer.readBoolean(),
                buffer.readBoolean(),
                buffer.readBoolean(),
                buffer.readBoolean(),
                buffer.readBoolean(),
                buffer.readVarInt(),
                buffer.readVarInt(),
                buffer.readVarInt(),
                buffer.readVarInt(),
                buffer.readUtf(256));
    }

    public static void handle(OpenPocketManagerPacket message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT,
                () -> () -> ClientHooks.openPocketManager(message)));
        context.setPacketHandled(true);
    }
}
