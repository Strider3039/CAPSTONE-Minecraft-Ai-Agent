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

import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.client.settings.KeyModifier;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.concurrent.ConcurrentHashMap;

@Mod(BotMod.MODID)
public class BotMod {
    // --- Singleton instance so ForgeWebSocketClient can access BotMod fields ---
    private static BotMod INSTANCE;
    public static BotMod getInstance() { return INSTANCE; }
    public static final String MODID = "ai_agent_bot";
    public static final Gson GSON = new GsonBuilder().create();

    private ForgeWebSocketClient wsClient;
    private boolean triedConnect = false;
    public final ConcurrentHashMap<Long, Long> latencyMap = new ConcurrentHashMap<>();
    private static final KeyMapping TOGGLE_KEY =
        new KeyMapping("key.aibot.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_P, "key.categories.misc");

    private boolean aiEnabled = true;  // start with AI control ON

    public BotMod() {
        MinecraftForge.EVENT_BUS.register(this);
        INSTANCE = this;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        Minecraft mc = Minecraft.getInstance();

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

        // --- Handle key toggle ---
        if (TOGGLE_KEY.consumeClick()) {
            aiEnabled = !aiEnabled;
            Minecraft.getInstance().player.displayClientMessage(
                Component.literal("AI control: " + (aiEnabled ? "ENABLED" : "DISABLED")),
                true
            );
        }

        // --- Only send observations when AI is enabled ---
        if (aiEnabled && event.phase == TickEvent.Phase.END && mc.player != null && mc.level != null) {
            if (mc.level.getGameTime() % 10 == 0 && wsClient != null && wsClient.isOpen()) {
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

        long seq = mc.level.getGameTime();
        latencyMap.put(seq, System.currentTimeMillis()); // record when this observation was sent

        JsonObject observation = new JsonObject();
        observation.addProperty("type", "observation");
        observation.addProperty("schema_version", "v0");
        observation.addProperty("seq", seq);
        observation.addProperty("timestamp", System.currentTimeMillis() / 1000.0);
        observation.add("payload", payload);

        String jsonMessage = GSON.toJson(observation);
        System.out.println("[AI-BOT] Sending observation: " + jsonMessage);

        if (wsClient != null && wsClient.isOpen()) {
            long sendTime = System.currentTimeMillis();
            latencyMap.put(mc.level.getGameTime(), sendTime);
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
    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(TOGGLE_KEY);
    }

    @SubscribeEvent
    public void onKeyInput(InputEvent.Key event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        if (TOGGLE_KEY.consumeClick()) {
            aiEnabled = !aiEnabled;
            mc.player.displayClientMessage(
                Component.literal("[AI-BOT] AI " + (aiEnabled ? "ENABLED" : "DISABLED")), true
            );
            System.out.println("[AI-BOT] AI " + (aiEnabled ? "ENABLED" : "DISABLED"));
        }
    }
}
