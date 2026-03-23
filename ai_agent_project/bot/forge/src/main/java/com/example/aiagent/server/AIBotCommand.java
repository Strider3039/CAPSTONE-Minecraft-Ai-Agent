package com.example.aiagent.server;

import com.example.aiagent.BotMod;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
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
                        .executes(ctx -> {
                            
                            showInfo(ctx.getSource());
                            ctx.getSource().sendSuccess(() -> Component.literal("Bots: " + BotMod.getInstance().getBotManager().getBotIds()), 
                            false);

                            return 1;
                        
                        })
                        
                    )
                .then(
                    Commands.literal("tptoself")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();

                            boolean ok = BotMod.getInstance().getBotManager().teleportBotToPlayer(player, "agent0");

                            if (ok) {
                                ctx.getSource().sendSuccess(() -> Component.literal("§a[AI-BOT] agent0 teleported to you."), false);
                                return 1;
                             } else {
                               ctx.getSource().sendFailure(Component.literal("§c[AI-BOT] Teleport failed (bot missing or different dimension)."));
                                return 0;
                            }
                        })
                )
                .then(
                    Commands.literal("soak")
                        .then(
                            Commands.literal("start")
                                .executes(ctx -> startSoak(ctx.getSource(), 3, 400))
                                .then(
                                    Commands.argument("episodes", IntegerArgumentType.integer(1))
                                        .executes(ctx -> startSoak(
                                                ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "episodes"),
                                                400))
                                        .then(
                                            Commands.argument("ticks_per_episode", IntegerArgumentType.integer(20))
                                                .executes(ctx -> startSoak(
                                                        ctx.getSource(),
                                                        IntegerArgumentType.getInteger(ctx, "episodes"),
                                                        IntegerArgumentType.getInteger(ctx, "ticks_per_episode")))
                                        )
                                )
                        )
                        .then(
                            Commands.literal("stop")
                                .executes(ctx -> stopSoak(ctx.getSource()))
                        )
                        .then(
                            Commands.literal("status")
                                .executes(ctx -> showSoakStatus(ctx.getSource()))
                        )
                )
        );
    }

    private static int spawnBot(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        if (server == null) {
            source.sendFailure(Component.literal("§c[AI-BOT] Server is null."));
            return 0;
        }

        ServerLevel level = server.overworld();
        if (level == null) {
            source.sendFailure(Component.literal("§c[AI-BOT] Failed to get overworld level."));
            return 0;
        }

       BotMod.getInstance().getBotManager().ensureDefaultBot(server, level);

       source.sendSuccess(
            () -> Component.literal("[AI-BOT] Spawned AI Bot in overworld."),
            true
       );
         return 1;
    }

    private static int showInfo(CommandSourceStack source) {
        var bots = BotMod.getInstance().getBotManager().getAllBots();

        if (bots == null || bots.isEmpty()) {
            source.sendFailure(Component.literal("§c[AI-BOT] No bots spawned."));
            return 0;
        }

        var bot = bots.iterator().next(); // ✅ no List indexing

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

    private static int startSoak(CommandSourceStack source, int episodes, int ticksPerEpisode) {
        MinecraftServer server = source.getServer();
        if (server == null) {
            source.sendFailure(Component.literal("§c[AI-BOT] Server is null."));
            return 0;
        }

        BotMod mod = BotMod.getInstance();
        if (mod == null || mod.getBotManager() == null || mod.getSoakController() == null) {
            source.sendFailure(Component.literal("§c[AI-BOT] Soak controller is unavailable."));
            return 0;
        }

        ServerLevel level = server.overworld();
        if (level == null) {
            source.sendFailure(Component.literal("§c[AI-BOT] Overworld is unavailable."));
            return 0;
        }

        mod.getSoakController().start(level, episodes, ticksPerEpisode);
        source.sendSuccess(() -> Component.literal(
                "§a[AI-BOT] Soak started."
                        + " episodes=" + episodes
                        + " ticks_per_episode=" + ticksPerEpisode), true);
        return 1;
    }

    private static int stopSoak(CommandSourceStack source) {
        BotMod mod = BotMod.getInstance();
        if (mod == null || mod.getSoakController() == null) {
            source.sendFailure(Component.literal("§c[AI-BOT] Soak controller is unavailable."));
            return 0;
        }

        mod.getSoakController().stop("manual_stop");
        source.sendSuccess(() -> Component.literal("§e[AI-BOT] Soak stopped."), true);
        return 1;
    }

    private static int showSoakStatus(CommandSourceStack source) {
        BotMod mod = BotMod.getInstance();
        if (mod == null || mod.getSoakController() == null) {
            source.sendFailure(Component.literal("§c[AI-BOT] Soak controller is unavailable."));
            return 0;
        }

        source.sendSuccess(() -> Component.literal(mod.getSoakController().getStatusLine()), false);
        return 1;
    }
}
