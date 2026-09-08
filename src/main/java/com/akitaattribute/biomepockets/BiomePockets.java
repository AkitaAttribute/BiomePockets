package com.akitaattribute.biomepockets;

import com.akitaattribute.biomepockets.network.NetworkHandler;
import com.akitaattribute.biomepockets.registry.ModItems;
import com.mojang.logging.LogUtils;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(BiomePockets.MOD_ID)
public class BiomePockets {
    public static final String MOD_ID = "biomepockets";
    public static final Logger LOGGER = LogUtils.getLogger();

    public BiomePockets() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModItems.register(modBus);
        modBus.addListener(this::commonSetup);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(NetworkHandler::register);
    }
}
