package com.akitaattribute.biomepockets.command;

import com.akitaattribute.biomepockets.BiomePockets;
import com.akitaattribute.biomepockets.registry.ModItems;
import com.akitaattribute.biomepockets.world.PocketClaimManager;
import com.akitaattribute.biomepockets.world.PocketDimensionManager;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.Registry;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.RegistryObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Mod.EventBusSubscriber(modid = BiomePockets.MOD_ID)
public final class BiomePocketsCommand {
    private static final String TRANSPORTER = "biome_transporter";
    private static final String SELECTOR = "biome_transporter_selector";
    private static final String MANAGER = "pocket_biome_manager";
    private static final SimpleCommandExceptionType UNKNOWN_ITEM = new SimpleCommandExceptionType(
            new TextComponent("Unknown BiomePockets item."));

    private BiomePocketsCommand() { }

    @SubscribeEvent
    public static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("biomepockets")
                .then(Commands.literal("dimensions")
                        .executes(context -> listDimensions(context.getSource())))
                .then(Commands.literal("expand")
                        .executes(context -> expandPocket(
                                context.getSource(),
                                context.getSource().getPlayerOrException()))
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(context -> expandPocket(
                                        context.getSource(),
                                        EntityArgument.getPlayer(context, "player")))))
                .then(Commands.argument("item", StringArgumentType.word())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                new String[] { TRANSPORTER, SELECTOR, MANAGER }, builder))
                        .executes(context -> giveItem(
                                context.getSource().getPlayerOrException(),
                                StringArgumentType.getString(context, "item")))));
    }

    private static int expandPocket(CommandSourceStack source, ServerPlayer target) {
        PocketClaimManager.handleAction(target, PocketClaimManager.Action.EXPAND);
        if (source.getEntity() != target) {
            source.sendSuccess(
                    new TextComponent("Requested biome pocket expansion for " + target.getGameProfile().getName() + "."),
                    false);
        }
        return 1;
    }

    @SuppressWarnings("deprecation")
    private static int listDimensions(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        Map<ResourceKey<Level>, ServerLevel> liveWorlds = server.forgeGetWorldMap();

        List<ResourceKey<Level>> loaded = new ArrayList<>(liveWorlds.keySet());
        loaded.sort(Comparator.comparing(key -> key.location().toString()));

        Registry<LevelStem> stemRegistry = server.getWorldData().worldGenSettings().dimensions();
        List<ResourceKey<LevelStem>> registered = new ArrayList<>();
        for (Map.Entry<ResourceKey<LevelStem>, LevelStem> entry : stemRegistry.entrySet()) {
            registered.add(entry.getKey());
        }
        registered.sort(Comparator.comparing(key -> key.location().toString()));

        source.sendSuccess(new TextComponent("Loaded ServerLevels (" + loaded.size() + "):"), false);
        for (ResourceKey<Level> key : loaded) {
            ServerLevel level = liveWorlds.get(key);
            String suffix = " [players=" + (level == null ? 0 : level.players().size()) + "]";
            if (PocketDimensionManager.isOwned(key)) {
                suffix += " [BiomePockets-owned]";
            }
            source.sendSuccess(new TextComponent(" - " + key.location() + suffix), false);
        }

        source.sendSuccess(new TextComponent("Registered LevelStems (" + registered.size() + "):"), false);
        for (ResourceKey<LevelStem> stemKey : registered) {
            ResourceKey<Level> levelKey = ResourceKey.create(Registry.DIMENSION_REGISTRY, stemKey.location());
            boolean isLoaded = liveWorlds.containsKey(levelKey);
            String suffix = " [loaded=" + (isLoaded ? "yes" : "no") + "]";
            if (PocketDimensionManager.isOwned(levelKey)) {
                suffix += " [BiomePockets-owned]";
            }
            source.sendSuccess(new TextComponent(" - " + stemKey.location() + suffix), false);
        }

        return loaded.size();
    }

    private static int giveItem(ServerPlayer player, String itemName) throws CommandSyntaxException {
        RegistryObject<Item> registryObject;

        if (TRANSPORTER.equals(itemName)) {
            registryObject = ModItems.BIOME_TRANSPORTER;
        } else if (SELECTOR.equals(itemName)) {
            registryObject = ModItems.BIOME_TRANSPORTER_SELECTOR;
        } else if (MANAGER.equals(itemName)) {
            registryObject = ModItems.POCKET_BIOME_MANAGER;
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
