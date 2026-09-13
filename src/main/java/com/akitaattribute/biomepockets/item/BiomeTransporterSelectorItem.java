package com.akitaattribute.biomepockets.item;

import com.akitaattribute.biomepockets.network.NetworkHandler;
import com.akitaattribute.biomepockets.world.BiomeCatalog;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public class BiomeTransporterSelectorItem extends Item {
    public BiomeTransporterSelectorItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            NetworkHandler.openSelector(serverPlayer, BiomeCatalog.getBiomeIds(serverPlayer.getServer()));
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }
}
