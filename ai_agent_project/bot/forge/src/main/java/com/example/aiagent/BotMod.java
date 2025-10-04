package com.example.aiagent;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.net.URI;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;


@Mod(BotMod.MODID)
public class BotMod {
    public static final String MODID = "ai_agent_bot";
    private ForgeWebSocketClient wsClient;

    public BotMod() {
        MinecraftForge.EVENT_BUS.register(this);
        try {
            wsClient = new ForgeWebSocketClient(new URI("ws://localhost:8080")); // Change to your server address/port
            wsClient.connect();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Print message every 10 seconds (200 ticks)
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.level != null && mc.level.getGameTime() % 200 == 0) {
            System.out.println("[AI-BOT] Player: " + mc.player.getName().getString());

            if (wsClient != null && wsClient.isOpen()) {
                wsClient.send("Hello from Minecraft! Player: " + mc.player.getName().getString());
            }
        }
    }

    // Register /aibot command2
    @SubscribeEvent
    public void onRegisterCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("aibot")
                .executes(ctx -> {
                    ctx.getSource().sendSystemMessage(Component.literal("AI bot is alive!"));
                    if (wsClient != null && wsClient.isOpen()) {
                        wsClient.send("Command executed: /aibot");
                    }
                    return 1;
                })
        );
    }
}
