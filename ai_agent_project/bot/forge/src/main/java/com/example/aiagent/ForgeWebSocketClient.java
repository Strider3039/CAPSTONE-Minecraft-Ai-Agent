package com.example.aiagent;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import com.google.gson.JsonObject;
import java.net.URI;

public class ForgeWebSocketClient extends WebSocketClient {
    private JsonObject lastAction = null;   // store last known valid action
    private long lastActionTime = 0;        // timestamp (ms) of when it was received


    public ForgeWebSocketClient(URI serverUri) {
        super(serverUri);
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        System.out.println("[WS] Connected to AI bridge");
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        System.out.println("[WS] Connection closed: " + reason);

        // Persistent reconnect loop
        new Thread(() -> {
            while (true) {
                try {
                    System.out.println("[WS] Attempting reconnect to AI bridge...");
                    this.reconnectBlocking();  // safer synchronous reconnect
                    System.out.println("[WS] Reconnected successfully!");
                    break;
                } catch (Exception e) {
                    System.err.println("[WS] Reconnect failed: " + e.getMessage());
                    try {
                        Thread.sleep(5000); // wait 5 seconds before retrying
                    } catch (InterruptedException ignored) {}
                }
            }
        }).start();
    }

    @Override
    public void onError(Exception ex) {
        System.err.println("[WS ERROR] " + ex.getMessage());
    }

    @Override
    public void onMessage(String message) {
        System.out.println("[AI-BOT] Received: " + message);

        try {
            JsonObject json = BotMod.GSON.fromJson(message, JsonObject.class);
            Minecraft mc = Minecraft.getInstance();

            mc.execute(() -> {
                if (mc.player == null) return;

                if (json.has("type") && "action".equals(json.get("type").getAsString()) && json.has("payload")) {
                    JsonObject payload = json.getAsJsonObject("payload");
                    // Save this as the last known action
                    lastAction = payload.deepCopy();
                    lastActionTime = System.currentTimeMillis();
                    long seq = json.has("seq") ? json.get("seq").getAsLong() : -1;

                    // --- Compute latency ---
                    Long sentTime = BotMod.getInstance().latencyMap.remove(seq);
                    if (sentTime != null) {
                        long latency = System.currentTimeMillis() - sentTime;
                        System.out.println("[AI-BOT] Round-trip latency: " + latency + " ms");
                        mc.player.displayClientMessage(
                            Component.literal("[AI-BOT] Latency: " + latency + " ms"), true
                        );
                    }

                    handleStructuredAction(payload, mc);
                    return;
                }

                // --- Fallback for direct/flat action messages ---
                if (json.has("action")) {
                    String action = json.get("action").getAsString();
                    JsonObject params = json.has("params") ? json.get("params").getAsJsonObject() : new JsonObject();
                    handleSimpleAction(action, params, mc);
                }
            });

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void handleStructuredAction(JsonObject payload, Minecraft mc) {
        if (payload.has("look")) {
            JsonObject look = payload.getAsJsonObject("look");
            float dYaw = look.get("dYaw").getAsFloat();
            float dPitch = look.get("dPitch").getAsFloat();
            mc.player.turn(dYaw, dPitch);
        }

        if (payload.has("move")) {
            JsonObject move = payload.getAsJsonObject("move");
            double forward = move.get("forward").getAsDouble();
            double strafe = move.get("strafe").getAsDouble();
            float moveSpeed = 0.1f;
            mc.player.moveRelative(moveSpeed, new net.minecraft.world.phys.Vec3((float) strafe, 0.0, (float) forward));
        }

        if (payload.has("jump") && payload.get("jump").getAsBoolean()) {
            if (mc.player.onGround()) {
                mc.player.jumpFromGround();
            }
        }
    }

    private void handleSimpleAction(String action, JsonObject params, Minecraft mc) {
        switch (action) {
            case "say":
                if (params.has("text")) {
                    String text = params.get("text").getAsString();
                    mc.player.displayClientMessage(Component.literal("[AI] " + text), false);
                }
                break;

            case "move":
                double dx = params.has("dx") ? params.get("dx").getAsDouble() : 0.0;
                double dy = params.has("dy") ? params.get("dy").getAsDouble() : 0.0;
                double dz = params.has("dz") ? params.get("dz").getAsDouble() : 0.0;
                mc.player.setPos(mc.player.getX() + dx, mc.player.getY() + dy, mc.player.getZ() + dz);
                break;

            case "look":
                float yaw = params.has("yaw") ? params.get("yaw").getAsFloat() : mc.player.getYRot();
                float pitch = params.has("pitch") ? params.get("pitch").getAsFloat() : mc.player.getXRot();
                mc.player.setYRot(yaw);
                mc.player.setXRot(pitch);
                break;

            case "debug":
                if (params.has("msg")) {
                    System.out.println("[AI-BOT DEBUG] " + params.get("msg").getAsString());
                }
                break;

            default:
                System.out.println("[AI-BOT] Unknown action: " + action);
                break;
        }
    }
    public void repeatLastActionIfNeeded(Minecraft mc) {
        if (lastAction == null) return;

        long now = System.currentTimeMillis();
        if (now - lastActionTime > 500 && now - lastActionTime < 3000) { 
            // If more than 0.5s since last AI message but less than 3s old
            handleStructuredAction(lastAction, mc);
        }
    }
}
