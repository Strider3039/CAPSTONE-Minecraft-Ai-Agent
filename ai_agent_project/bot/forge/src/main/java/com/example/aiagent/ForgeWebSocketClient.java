package com.example.aiagent;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import com.google.gson.JsonObject;
import java.net.URI;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;

public class ForgeWebSocketClient extends WebSocketClient {
    private final Map<String, Long> nextAllowed = new HashMap<>();
    private static final long ATTACK_COOLDOWN_MS = 150;
    private static final long USE_COOLDOWN_MS    = 150;
    private static final long PLACE_COOLDOWN_MS  = 150;

    private static final int MAX_INFLIGHT = 32;
    private final ArrayBlockingQueue<JsonObject> inflight = new ArrayBlockingQueue<>(MAX_INFLIGHT);

    private Runnable onReconnect = null;
    public void setOnReconnect(Runnable r) { this.onReconnect = r; }

    private long lastAckSeq = 0;

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
                    System.out.println("[WS] Attempting reconnect…");
                    this.reconnectBlocking();  // safer synchronous reconnect
                    System.out.println("[WS] Reconnected successfully!");
                    inflight.clear();
                    emitBridgeHealth("info", "reconnected");
                    if (onReconnect != null) onReconnect.run();
                    break;
                } catch (Exception e) {
                    System.err.println("[WS] Reconnect failed: " + e.getMessage());
                    try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
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
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        try {
            JsonObject json = BotMod.GSON.fromJson(message, JsonObject.class);
            if (json == null) return;

            if (json.has("kind") && "action".equals(json.get("kind").getAsString())) {
                long seq = json.has("seq") ? json.get("seq").getAsLong() : -1;
                String actionId = json.has("action_id") ? json.get("action_id").getAsString() : "unknown";

                if (seq <= lastAckSeq) {
                    System.out.println("[WS] Ignoring stale action seq=" + seq);
                    return;
                }
                lastAckSeq = Math.max(lastAckSeq, seq);

                JsonObject payload = json.getAsJsonObject("payload");
                if (payload == null) return;

                if (inflight.remainingCapacity() == 0) {
                    inflight.poll();
                    emitBridgeHealth("warn", "dropped_input");
                }
                inflight.offer(payload);

                // Execute the action safely on the main game thread
                mc.execute(() -> handleStructuredAction(actionId, payload, mc));
            }
        } catch (Exception e) {
            System.err.println("[WS] Parse error: " + e.getMessage());
        }
    }


    // ───────────────────────────── Action handling ─────────────────────────────
    private void handleStructuredAction(String actionId, JsonObject payload, Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null) return;

        // LOOK
        if (payload.has("look")) {
            JsonObject look = payload.getAsJsonObject("look");
            float dYaw = look.has("dYaw") ? look.get("dYaw").getAsFloat() : 0f;
            float dPitch = look.has("dPitch") ? look.get("dPitch").getAsFloat() : 0f;
            p.turn(dYaw, dPitch);
            emitActionResult(actionId, "success", "");
        }

        // MOVE / STRAFE
        if (payload.has("move")) {
            JsonObject move = payload.getAsJsonObject("move");
            double forward = move.has("forward") ? move.get("forward").getAsDouble() : 0;
            double strafe  = move.has("strafe") ? move.get("strafe").getAsDouble() : 0;
            float moveSpeed = 0.1f;
            p.moveRelative(moveSpeed, new net.minecraft.world.phys.Vec3((float)strafe, 0.0f, (float)forward));
            emitActionResult(actionId, "success", "");
        }

        // JUMP
        if (payload.has("jump") && payload.get("jump").getAsBoolean()) {
            if (p.onGround()) {
                p.jumpFromGround();
                emitActionResult(actionId, "success", "");
            } else {
                emitActionResult(actionId, "fail", "not_grounded");
            }
        }

        // SNEAK
        if (payload.has("sneak")) {
            boolean sneak = payload.get("sneak").getAsBoolean();
            p.setShiftKeyDown(sneak);
            emitActionResult(actionId, "success", "");
        }

        // SELECT SLOT
        if (payload.has("select_slot")) {
            int slot = payload.get("select_slot").getAsInt();
            slot = Math.max(0, Math.min(8, slot));
            p.getInventory().pickSlot(slot);
            emitActionResult(actionId, "success", "");
        }

        // ATTACK
        if (payload.has("attack") && payload.get("attack").getAsBoolean()) {
            if (inCooldown("attack")) {
                emitActionResult(actionId, "cooldown", "attack_cooldown");
            } else {
                var hit = p.pick(5.0D, 0.0F, false);
                if (hit.getType() == net.minecraft.world.phys.HitResult.Type.ENTITY) {
                    var target = ((net.minecraft.world.phys.EntityHitResult) hit).getEntity();
                    Minecraft.getInstance().gameMode.attack(p, target);
                } else {
                    p.swing(p.getUsedItemHand()); // no target, just swing
                }
                setCooldown("attack", ATTACK_COOLDOWN_MS);
                emitActionResult(actionId, "success", "");
            }
        }

        // USE
        if (payload.has("use") && payload.get("use").getAsBoolean()) {
            if (inCooldown("use")) {
                emitActionResult(actionId, "cooldown", "use_cooldown");
            } else {
                mc.gameMode.useItem(p, p.getUsedItemHand());
                setCooldown("use", USE_COOLDOWN_MS);
                emitActionResult(actionId, "success", "");
            }
        }

        // PLACE
        if (payload.has("place") && payload.get("place").getAsBoolean()) {
            if (inCooldown("place")) {
                emitActionResult(actionId, "cooldown", "place_cooldown");
            } else {
                var hit = p.pick(5.0D, 0.0F, false);
                if (hit instanceof net.minecraft.world.phys.BlockHitResult bhr) {
                    Minecraft.getInstance().gameMode.useItemOn(p, p.getUsedItemHand(), bhr);
                }
                setCooldown("place", PLACE_COOLDOWN_MS);
                emitActionResult(actionId, "success", "");
            }
        }
    }


    // ───────────────────────────── Cooldown helpers ─────────────────────────────
    private boolean inCooldown(String kind) {
        long now = System.currentTimeMillis();
        return nextAllowed.getOrDefault(kind, 0L) > now;
    }

    private void setCooldown(String kind, long ms) {
        nextAllowed.put(kind, System.currentTimeMillis() + ms);
    }


    // ───────────────────────────── Feedback emitters ─────────────────────────────
    private void emitActionResult(String actionId, String status, String reason) {
        JsonObject payload = new JsonObject();
        JsonObject result = new JsonObject();

        result.addProperty("action_id", actionId);
        result.addProperty("status", status);
        result.addProperty("reason", reason);
        result.addProperty("server_tick",
                Minecraft.getInstance().level != null ? Minecraft.getInstance().level.getGameTime() : 0);
        result.addProperty("ts_server", System.currentTimeMillis() / 1000.0);
        result.addProperty("latency_ms", 0);

        payload.add("action_result", result);

        JsonObject evt = new JsonObject();
        evt.addProperty("proto", "1");
        evt.addProperty("kind", "action_result");
        evt.addProperty("timestamp", System.currentTimeMillis());
        evt.add("payload", payload);

        send(evt.toString());
    }

    private void emitBridgeHealth(String level, String detail) {
        JsonObject payload = new JsonObject();
        JsonObject health = new JsonObject();
        health.addProperty("level", level);
        health.addProperty("detail", detail);
        payload.add("bridge_health", health);

        JsonObject evt = new JsonObject();
        evt.addProperty("proto", "1");
        evt.addProperty("kind", "bridge_health");
        evt.addProperty("timestamp", System.currentTimeMillis());
        evt.add("payload", payload);

        send(evt.toString());
    }
}
