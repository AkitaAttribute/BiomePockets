package com.akitaattribute.biomepockets.registry;

import com.akitaattribute.biomepockets.BiomePockets;
import com.akitaattribute.biomepockets.item.BiomeTransporterItem;
import com.akitaattribute.biomepockets.item.BiomeTransporterSelectorItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModItems {
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, BiomePockets.MOD_ID);

    public static final RegistryObject<Item> BIOME_TRANSPORTER = ITEMS.register("biome_transporter",
            () -> new BiomeTransporterItem(new Item.Properties().tab(CreativeModeTab.TAB_TOOLS).stacksTo(1)));

    public static final RegistryObject<Item> BIOME_TRANSPORTER_SELECTOR = ITEMS.register("biome_transporter_selector",
            () -> new BiomeTransporterSelectorItem(new Item.Properties().tab(CreativeModeTab.TAB_TOOLS).stacksTo(1)));

    private ModItems() { }

    public static void register(IEventBus bus) {
        ITEMS.register(bus);
    }
}
