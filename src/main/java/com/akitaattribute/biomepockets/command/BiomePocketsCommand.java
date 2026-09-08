package com.akitaattribute.biomepockets.command;

import com.akitaattribute.biomepockets.BiomePockets;
import com.akitaattribute.biomepockets.registry.ModItems;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.RegistryObject;

@Mod.EventBusSubscriber(modid = BiomePockets.MOD_ID)
public final class BiomePocketsCommand {
    private static final String TRANSPORTER = "biome_transporter";
    private static final String SELECTOR = "biome_transporter_selector";
    private static final SimpleCommandExceptionType UNKNOWN_ITEM = new SimpleCommandExceptionType(
            new TextComponent("Unknown BiomePockets item."));

    private BiomePocketsCommand() { }

    @SubscribeEvent
    public static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("biomepockets")
                .then(Commands.argument("item", StringArgumentType.word())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                new String[] { TRANSPORTER, SELECTOR }, builder))
                        .executes(context -> giveItem(
                                context.getSource().getPlayerOrException(),
                                StringArgumentType.getString(context, "item")))));
    }

    private static int giveItem(ServerPlayer player, String itemName) throws CommandSyntaxException {
        RegistryObject<Item> registryObject;

        if (TRANSPORTER.equals(itemName)) {
            registryObject = ModItems.BIOME_TRANSPORTER;
        } else if (SELECTOR.equals(itemName)) {
            registryObject = ModItems.BIOME_TRANSPORTER_SELECTOR;
        } else {
            throw UNKNOWN_ITEM.create();
        }

        ItemStack stack = new ItemStack(registryObject.get());
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }

        player.displayClientMessage(new TextComponent("Given " + stack.getHoverName().getString() + "."), false);
        return 1;
    }
}
