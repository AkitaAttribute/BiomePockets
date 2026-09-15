package com.akitaattribute.biomepockets.network;

import com.akitaattribute.biomepockets.BiomePockets;
import com.akitaattribute.biomepockets.world.PocketDebugSettings;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Server-bound runtime debug tuning update from the transporter selector. */
public record UpdateDebugSettingsPacket(int heightProbeAxis, int generationOpsPerTick) {
    public static void encode(UpdateDebugSettingsPacket message, FriendlyByteBuf buffer) {
        buffer.writeVarInt(message.heightProbeAxis);
        buffer.writeVarInt(message.generationOpsPerTick);
    }

    public static UpdateDebugSettingsPacket decode(FriendlyByteBuf buffer) {
        return new UpdateDebugSettingsPacket(buffer.readVarInt(), buffer.readVarInt());
    }

    public static void handle(
            UpdateDebugSettingsPacket message,
            Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer sender = context.getSender();
        context.enqueueWork(() -> {
            if (!PocketDebugSettings.canEdit(sender)) {
                return;
            }

            int probes = PocketDebugSettings.normalizeProbeAxis(message.heightProbeAxis());
            int ops = Math.max(
                    1,
                    Math.min(PocketDebugSettings.MAX_GENERATION_OPS_PER_TICK,
                            message.generationOpsPerTick()));
            PocketDebugSettings.update(probes, ops);
            BiomePockets.LOGGER.info(
                    "{} changed BiomePockets debug generation settings: heightProbeAxis={}, generationOpsPerTick={}",
                    sender == null ? "<unknown>" : sender.getGameProfile().getName(),
                    probes,
                    ops);
        });
        context.setPacketHandled(true);
    }
}
