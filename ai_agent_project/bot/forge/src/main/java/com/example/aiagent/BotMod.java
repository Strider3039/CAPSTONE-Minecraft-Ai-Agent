package com.example.aiagent;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;


@Mod(BotMod.MODID)
public class BotMod {
    public static final String MODID = "ai_agent_bot";

    public BotMod() {
        // Register events on the Forge event bus
        MinecraftForge.EVENT_BUS.register(this);
    }

    // Test: print message on tick
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.level != null && mc.level.getGameTime() % 200 == 0) {
            System.out.println("[AI-BOT] Player: " + mc.player.getName().getString());
        }
    }

    // Register a simple /aibot command
    @SubscribeEvent
    public void onRegisterCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("aibot")
                .executes(ctx -> {
                    ctx.getSource().sendSystemMessage(Component.literal("AI bot is alive!"));
                    return 1;
                })
        );
    }
}
