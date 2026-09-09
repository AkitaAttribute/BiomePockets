package com.akitaattribute.biomepockets.registry;

import com.akitaattribute.biomepockets.BiomePockets;
import com.akitaattribute.biomepockets.item.BiomeTransporterItem;
import com.akitaattribute.biomepockets.item.BiomeTransporterSelectorItem;
import com.akitaattribute.biomepockets.item.PocketBiomeManagerItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModItems {
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, BiomePockets.MOD_ID);

    public static final RegistryObject<Item> BIOME_TRANSPORTER = ITEMS.register(
            "biome_transporter",
            () -> new BiomeTransporterItem(new Item.Properties()
                    .tab(CreativeModeTab.TAB_MISC)
                    .stacksTo(1)));

    public static final RegistryObject<Item> BIOME_TRANSPORTER_SELECTOR = ITEMS.register(
            "biome_transporter_selector",
            () -> new BiomeTransporterSelectorItem(new Item.Properties()
                    .tab(CreativeModeTab.TAB_MISC)
                    .stacksTo(1)));

    public static final RegistryObject<Item> POCKET_BIOME_MANAGER = ITEMS.register(
            "pocket_biome_manager",
            () -> new PocketBiomeManagerItem(new Item.Properties()
                    .tab(CreativeModeTab.TAB_MISC)
                    .stacksTo(1)));

    private ModItems() { }
}
