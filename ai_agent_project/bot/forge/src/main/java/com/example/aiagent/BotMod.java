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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

@Mod(BotMod.MODID)
public class BotMod {
    public static final String MODID = "ai_agent_bot";
    public static final Gson GSON = new GsonBuilder().create();

    private ForgeWebSocketClient wsClient;
    private boolean triedConnect = false;

    public BotMod() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        Minecraft mc = Minecraft.getInstance();

        // --- Connect once when player exists ---
        if (!triedConnect && mc.player != null) {
            triedConnect = true;
            try {
                wsClient = new ForgeWebSocketClient(new URI("ws://127.0.0.1:8765"));
                wsClient.connect();
                System.out.println("[AI-BOT] Attempting to connect...");
            } catch (Exception e) {
                System.err.println("[AI-BOT] Failed to connect to WebSocket:");
                e.printStackTrace();
            }
        }

        // --- Every ~10 seconds, send an observation if connected ---
        if (event.phase == TickEvent.Phase.END && mc.player != null && mc.level != null) {
            if (mc.level.getGameTime() % 1 == 0 && wsClient != null && wsClient.isOpen()) {
                sendObservation(mc);
            }
        }
    }

    private void sendObservation(Minecraft mc) {
        JsonObject pos = new JsonObject();
        pos.addProperty("x", mc.player.getX());
        pos.addProperty("y", mc.player.getY());
        pos.addProperty("z", mc.player.getZ());

        JsonObject pose = new JsonObject();
        pose.add("pos", pos);
        pose.addProperty("yaw", mc.player.getYRot());
        pose.addProperty("pitch", mc.player.getXRot());

        JsonArray rays = new JsonArray();
        rays.add(1.0);
        rays.add(0.8);
        rays.add(0.7);

        JsonArray hotbar = new JsonArray();
        for (int i = 0; i < 9; i++) hotbar.add((String) null);

        JsonObject payload = new JsonObject();
        payload.add("pose", pose);
        payload.add("rays", rays);
        payload.add("hotbar", hotbar);

        JsonObject observation = new JsonObject();
        observation.addProperty("type", "observation");
        observation.addProperty("schema_version", "v0");
        observation.addProperty("seq", mc.level.getGameTime());
        observation.addProperty("timestamp", System.currentTimeMillis() / 1000.0);
        observation.add("payload", payload);

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
