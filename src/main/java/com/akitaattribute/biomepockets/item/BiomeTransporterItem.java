package com.akitaattribute.biomepockets.item;

import com.akitaattribute.biomepockets.world.BiomeCatalog;
import com.akitaattribute.biomepockets.world.PocketDimensionManager;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.List;

public class BiomeTransporterItem extends Item {
    public BiomeTransporterItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            List<ResourceLocation> biomes = BiomeCatalog.getBiomeIds(serverPlayer.getServer());
            if (biomes.isEmpty()) {
                serverPlayer.displayClientMessage(new TextComponent("BiomePockets could not find any registered biomes."), false);
                return InteractionResultHolder.fail(stack);
            }

            ResourceLocation selected = biomes.get(serverPlayer.getRandom().nextInt(biomes.size()));
            PocketDimensionManager.createAndTeleport(serverPlayer, selected);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }
}
