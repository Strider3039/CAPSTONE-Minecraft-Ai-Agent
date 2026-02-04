package com.example.aiagent.server;

import com.example.aiagent.BotMod;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = BotMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class AIBotCommand {
    
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("agent")
                .requires(src -> src.hasPermission(2)) // OP level 2
                .then(
                    Commands.literal("spawn")
                        .executes(ctx -> spawnBot(ctx.getSource()))
                )
                .then(
                    Commands.literal("info")
                        .executes(ctx -> showInfo(ctx.getSource()))
                )   
        );
    }

    private static int spawnBot(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        ServerLevel level = server.overworld();

        if (server == null || level == null) {
            source.sendFailure(Component.literal("§c[AI-BOT] Failed to get overworld level."));
            return 0;
        }

       ServerBotHooks.BOTS.ensureDefaultBot(server, level);

       source.sendSuccess(
            () -> Component.literal("[AI-BOT] Spawned AI Bot in overworld."),
            true
       );
         return 1;
    }

    private static int showInfo(CommandSourceStack source) {
        var bots = ServerBotHooks.BOTS.getAllBots();
        
        if (bots.isEmpty()) {
            source.sendFailure(Component.literal("§c[AI-BOT] No bots spawned."));
            return 0;
        }
        
        var bot = bots.get(0); // Get first bot
        if (bot.player == null) {
            source.sendFailure(Component.literal("§c[AI-BOT] Bot player is null."));
            return 0;
        }
        
        String dimension = bot.player.level().dimension().location().toString();
        double x = bot.player.getX();
        double y = bot.player.getY();
        double z = bot.player.getZ();
        
        source.sendSuccess(
            () -> Component.literal(String.format(
                "§a[AI-BOT] Dimension: %s | Pos: (%.2f, %.2f, %.2f)",
                dimension, x, y, z
            )),
            false
        );
        return 1;
    }
}
