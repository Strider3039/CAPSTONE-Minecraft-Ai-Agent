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

import com.google.gson.JsonObject;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;


@Mod(BotMod.MODID)
public class BotMod {
    public static final String MODID = "ai_agent_bot";
    public static final Gson GSON = new GsonBuilder().create();

    private ForgeWebSocketClient wsClient;

    public BotMod() {
        MinecraftForge.EVENT_BUS.register(this);
        try {
            wsClient = new ForgeWebSocketClient(new URI("ws://localhost:8080")); // update server address if needed
            wsClient.connect();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (event.phase == TickEvent.Phase.END && mc.player != null && mc.level != null) {
            if (mc.level.getGameTime() % 200 == 0) { // every 200 ticks (~10s)
                sendObservation(mc);
            }
        }
    }

    private void sendObservation(Minecraft mc) {
        JsonObject observation = new JsonObject();
        observation.addProperty("event", "observation");
        observation.addProperty("timestamp", System.currentTimeMillis());
        observation.addProperty("player_name", mc.player.getName().getString());
        observation.addProperty("health", mc.player.getHealth());
        observation.addProperty("x", mc.player.getX());
        observation.addProperty("y", mc.player.getY());
        observation.addProperty("z", mc.player.getZ());

        String jsonMessage = GSON.toJson(observation);
        System.out.println("[AI-BOT] Sending observation: " + jsonMessage);

        if (wsClient != null && wsClient.isOpen()) {
            wsClient.send(jsonMessage);
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("aibot")
                .executes(ctx -> {
                    ctx.getSource().sendSystemMessage(Component.literal("AI bot is alive!"));
                    if (wsClient != null && wsClient.isOpen()) {
                        JsonObject ping = new JsonObject();
                        ping.addProperty("event", "command");
                        ping.addProperty("cmd", "/aibot");
                        wsClient.send(GSON.toJson(ping));
                    }
                    return 1;
                })
        );
    }
}
