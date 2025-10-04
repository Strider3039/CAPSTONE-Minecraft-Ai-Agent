package com.example.aiagent;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import com.google.gson.JsonObject;

import java.net.URI;

public class ForgeWebSocketClient extends WebSocketClient {

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
    }

    @Override
    public void onError(Exception ex) {
        ex.printStackTrace();
    }

    @Override
    public void onMessage(String message) {
        System.out.println("[AI-BOT] Received: " + message);

        try {
            JsonObject json = BotMod.GSON.fromJson(message, JsonObject.class);
            Minecraft mc = Minecraft.getInstance();

            mc.execute(() -> {
                if (mc.player == null) return;

                // --- NEW: handle structured dummy.py messages ---
                if (json.has("look") || json.has("move") || json.has("jump")) {
                    handleStructuredAction(json, mc);
                    return;
                }

                // --- existing flat action messages ---
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

    private void handleStructuredAction(JsonObject json, Minecraft mc) {
        // --- Handle "look" ---
        if (json.has("look")) {
            JsonObject look = json.getAsJsonObject("look");
            float dYaw = look.has("dYaw") ? look.get("dYaw").getAsFloat() : 0f;
            float dPitch = look.has("dPitch") ? look.get("dPitch").getAsFloat() : 0f;
            mc.player.turn(dYaw, dPitch);
        }

        // --- Handle "move" ---
        if (json.has("move")) {
            JsonObject move = json.getAsJsonObject("move");
            double forward = move.has("forward") ? move.get("forward").getAsDouble() : 0.0;
            double strafe = move.has("strafe") ? move.get("strafe").getAsDouble() : 0.0;

            // Use moveRelative to apply movement based on player's facing direction
            float moveSpeed = 0.1f; // adjust for smoother or faster motion
            mc.player.moveRelative(moveSpeed, new net.minecraft.world.phys.Vec3((float) strafe, 0.0, (float) forward));
        }

        // --- Handle "jump" ---
        if (json.has("jump") && json.get("jump").getAsBoolean()) {
            if (mc.player.onGround()) {  // method name updated in 1.21.x
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


}
