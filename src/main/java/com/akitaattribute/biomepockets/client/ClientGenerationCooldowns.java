package com.akitaattribute.biomepockets.client;

import com.akitaattribute.biomepockets.BiomePockets;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Registry;
import net.minecraft.network.chat.ChatType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemCooldowns;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Keeps the vanilla item cooldown overlay pinned to actual pocket-generation progress.
 * The server sends completed/total work units; this client helper rewrites the local
 * cooldown instance each tick so elapsed wall-clock time cannot move the bar ahead of
 * the generation process.
 */
@Mod.EventBusSubscriber(modid = BiomePockets.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientGenerationCooldowns {
    private static final int PROGRESS_SCALE = 10_000;
    private static final Map<Item, Progress> ACTIVE = new IdentityHashMap<>();

    private ClientGenerationCooldowns() { }

    public static void update(ResourceLocation itemId, int completed, int total, boolean active) {
        Item item = Registry.ITEM.get(itemId);
        if (item == null) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (!active || total <= 0 || completed >= total) {
            ACTIVE.remove(item);
            if (minecraft.player != null) {
                minecraft.player.getCooldowns().removeCooldown(item);
            }
            return;
        }

        Progress progress = new Progress(Math.max(0, completed), Math.max(1, total));
        ACTIVE.put(item, progress);
        pinCooldown(item, progress);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            ACTIVE.clear();
            return;
        }

        for (Map.Entry<Item, Progress> entry : ACTIVE.entrySet()) {
            pinCooldown(entry.getKey(), entry.getValue());
        }
    }

    /**
     * Build 105 emitted action-bar loading text from the throttled generator. Keep
     * server-side error messages intact, but suppress only those known loading strings
     * now that generation progress is represented by the item's cooldown overlay.
     */
    @SubscribeEvent
    public static void onChatReceived(ClientChatReceivedEvent event) {
        if (event.getType() != ChatType.GAME_INFO) {
            return;
        }

        String text = event.getMessage().getString();
        boolean progressMessage = text.startsWith("Preparing biome pocket: ");
        boolean initialMessage = text.startsWith("Preparing ")
                && text.contains(" biome pocket (")
                && (text.endsWith("...") || text.contains("server-friendly speed"));
        if (progressMessage || initialMessage) {
            event.setCanceled(true);
        }
    }

    private static void pinCooldown(Item item, Progress progress) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }

        ItemCooldowns cooldowns = minecraft.player.getCooldowns();
        int completedUnits = Math.min(progress.completed(), progress.total()) * PROGRESS_SCALE;
        int totalUnits = progress.total() * PROGRESS_SCALE;
        int remainingUnits = Math.max(1, totalUnits - completedUnits);
        int now = cooldowns.tickCount;

        cooldowns.cooldowns.put(
                item,
                new ItemCooldowns.CooldownInstance(
                        now - completedUnits,
                        now + remainingUnits));
    }

    private record Progress(int completed, int total) { }
}
