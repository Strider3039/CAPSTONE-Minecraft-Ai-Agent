package com.example.aiagent;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.net.URI;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class ForgeWebSocketClient extends WebSocketClient {
    private final Map<String, Long> nextAllowed = new HashMap<>();
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);
    private final AtomicLong seqCounter = new AtomicLong(0);

    private static final long ATTACK_COOLDOWN_MS = 150;
    private static final long USE_COOLDOWN_MS    = 150;
    private static final long PLACE_COOLDOWN_MS  = 150;

    private static final int MAX_INFLIGHT = 32;
    private final ArrayBlockingQueue<JsonObject> inflight = new ArrayBlockingQueue<>(MAX_INFLIGHT);

    private Runnable onReconnect = null;
    public void setOnReconnect(Runnable r) { this.onReconnect = r; }

    private long lastAckSeq = -1;

    public ForgeWebSocketClient(URI serverUri) {
        super(serverUri);
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        System.out.println("[WS] Connected to AI bridge");
        emitBridgeHealth("info", "connected");
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        System.out.println("[WS] Connection closed: " + reason);

        // Prevent overlapping reconnects
        if (reconnecting.getAndSet(true)) return;

        new Thread(() -> {
            while (!isOpen()) {
                try {
                    System.out.println("[WS] Attempting reconnect...");
                    reconnectBlocking();  // blocks until connected or fails
                    System.out.println("[WS] Reconnected successfully!");
                    inflight.clear();
                    emitBridgeHealth("info", "reconnected");
                    if (onReconnect != null) onReconnect.run();
                    reconnecting.set(false);
                    break;
                } catch (Exception e) {
                    System.err.println("[WS] Reconnect failed: " + e.getMessage());
                    try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
                }
            }
        }, "WS-Reconnector").start();
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

            // ───── Handle ACTION messages ─────
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

                System.out.println("[WS] Action received: " + payload.toString());

                // Execute the action safely on the main game thread
                mc.execute(() -> handleStructuredAction(actionId, payload, mc));
                return;
            }

            // ───── Handle COMMAND messages (e.g., /tp, /say) ─────
            if (json.has("kind") && "command".equals(json.get("kind").getAsString())) {
                JsonObject payload = json.getAsJsonObject("payload");
                if (payload != null && payload.has("cmd")) {
                    String command = payload.get("cmd").getAsString();
                    mc.execute(() -> {
                        if (mc.player != null && mc.player.connection != null) {
                            mc.player.connection.sendCommand(command);
                            mc.player.displayClientMessage(
                                net.minecraft.network.chat.Component.literal("§a[AI] Executed: " + command),
                                true
                            );
                            System.out.println("[WS] Executed command: " + command);
                        } else {
                            System.err.println("[WS] Skipped command (no player): " + command);
                        }
                    });
                }
                return;
            }

        } catch (Exception e) {
            System.err.println("[WS] Parse error: " + e.getMessage());
        }
    }

    // ───────────────────────────── Action handling ─────────────────────────────
    private void handleStructuredAction(String actionId, JsonObject payload, Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null) return;

        // Aggregate outcome across all sub-ops (schema expects one action_result per action_id)
        String overallStatus = "success";
        List<String> reasons = new ArrayList<>();

        // LOOK
        if (payload.has("look")) {
            try {
                JsonObject look = payload.getAsJsonObject("look");
                float dYaw = look.has("dYaw") ? look.get("dYaw").getAsFloat() : 0f;
                float dPitch = look.has("dPitch") ? look.get("dPitch").getAsFloat() : 0f;
                p.turn(dYaw, dPitch);
            } catch (Exception e) {
                overallStatus = "fail";
                reasons.add("look_error");
            }
        }

        // MOVE / STRAFE (applies an immediate impulse this tick)
        if (payload.has("move")) {
            try {
                JsonObject move = payload.getAsJsonObject("move");
                double forward = move.has("forward") ? move.get("forward").getAsDouble() : 0;
                double strafe  = move.has("strafe") ? move.get("strafe").getAsDouble() : 0;
                float moveSpeed = 0.1f;
                p.moveRelative(moveSpeed, new net.minecraft.world.phys.Vec3((float)strafe, 0.0f, (float)forward));
            } catch (Exception e) {
                overallStatus = "fail";
                reasons.add("move_error");
            }
        }

        // JUMP
        if (payload.has("jump") && safeGetBool(payload, "jump")) {
            if (p.onGround()) {
                try {
                    p.jumpFromGround();
                } catch (Exception e) {
                    overallStatus = "fail";
                    reasons.add("jump_error");
                }
            } else {
                // not an error, but action can't complete now
                overallStatus = worstOf(overallStatus, "fail");
                reasons.add("not_grounded");
            }
        }

        // SNEAK
        if (payload.has("sneak")) {
            try {
                boolean sneak = payload.get("sneak").getAsBoolean();
                p.setShiftKeyDown(sneak);
            } catch (Exception e) {
                overallStatus = "fail";
                reasons.add("sneak_error");
            }
        }

        // SELECT SLOT
        if (payload.has("select_slot")) {
            try {
                int slot = payload.get("select_slot").getAsInt();
                slot = Math.max(0, Math.min(8, slot));
                p.getInventory().pickSlot(slot);
            } catch (Exception e) {
                overallStatus = "fail";
                reasons.add("select_slot_error");
            }
        }

        // ATTACK
        if (payload.has("attack") && safeGetBool(payload, "attack")) {
            if (inCooldown("attack")) {
                overallStatus = worstOf(overallStatus, "cooldown");
                reasons.add("attack_cooldown");
            } else {
                try {
                    var hit = p.pick(5.0D, 0.0F, false);
                    if (hit.getType() == net.minecraft.world.phys.HitResult.Type.ENTITY) {
                        var target = ((net.minecraft.world.phys.EntityHitResult) hit).getEntity();
                        Minecraft.getInstance().gameMode.attack(p, target);
                    } else {
                        p.swing(p.getUsedItemHand()); // no target, just swing
                    }
                    setCooldown("attack", ATTACK_COOLDOWN_MS);
                } catch (Exception e) {
                    overallStatus = "fail";
                    reasons.add("attack_error");
                }
            }
        }

        // USE
        if (payload.has("use") && safeGetBool(payload, "use")) {
            if (inCooldown("use")) {
                overallStatus = worstOf(overallStatus, "cooldown");
                reasons.add("use_cooldown");
            } else {
                try {
                    mc.gameMode.useItem(p, p.getUsedItemHand());
                    setCooldown("use", USE_COOLDOWN_MS);
                } catch (Exception e) {
                    overallStatus = "fail";
                    reasons.add("use_error");
                }
            }
        }

        // PLACE
        if (payload.has("place") && safeGetBool(payload, "place")) {
            if (inCooldown("place")) {
                overallStatus = worstOf(overallStatus, "cooldown");
                reasons.add("place_cooldown");
            } else {
                try {
                    var hit = p.pick(5.0D, 0.0F, false);
                    if (hit instanceof net.minecraft.world.phys.BlockHitResult bhr) {
                        Minecraft.getInstance().gameMode.useItemOn(p, p.getUsedItemHand(), bhr);
                    }
                    setCooldown("place", PLACE_COOLDOWN_MS);
                } catch (Exception e) {
                    overallStatus = "fail";
                    reasons.add("place_error");
                }
            }
        }

        // Emit exactly one action_result for this action_id (schema-compliant)
        String reason = String.join(",", reasons);
        emitActionResult(actionId, overallStatus, reason);
    }

    private static boolean safeGetBool(JsonObject obj, String name) {
        try { return obj.get(name).getAsBoolean(); } catch (Exception e) { return false; }
    }

    private static String worstOf(String a, String b) {
        // Failure precedence: fail > cooldown > success
        if ("fail".equals(a) || "fail".equals(b)) return "fail";
        if ("cooldown".equals(a) || "cooldown".equals(b)) return "cooldown";
        return "success";
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
        result.addProperty("status", status); // enum: success|fail|cooldown|blocked|timeout
        if (reason != null && !reason.isEmpty()) {
            result.addProperty("reason", reason);
        }
        result.addProperty("server_tick",
                Minecraft.getInstance().level != null ? Minecraft.getInstance().level.getGameTime() : 0);
        result.addProperty("ts_server", System.currentTimeMillis() / 1000.0);
        result.addProperty("latency_ms", 0);

        payload.add("action_result", result);

        JsonObject evt = new JsonObject();
        evt.addProperty("proto", "1"); // string per schema
        evt.addProperty("kind", "action_result");
        evt.addProperty("seq", seqCounter.incrementAndGet()); // optional but helpful
        evt.addProperty("timestamp", System.currentTimeMillis() / 1000.0);
        evt.add("payload", payload);

        send(evt.toString());
    }

    private void emitBridgeHealth(String level, String detail) {
        JsonObject payload = new JsonObject();
        JsonObject health = new JsonObject();
        health.addProperty("level", level);  // enum: info|warn|error
        health.addProperty("detail", detail);
        payload.add("bridge_health", health);

        JsonObject evt = new JsonObject();
        evt.addProperty("proto", "1"); // string per schema
        evt.addProperty("kind", "bridge_health");
        evt.addProperty("seq", seqCounter.incrementAndGet()); // optional
        evt.addProperty("timestamp", System.currentTimeMillis() / 1000.0);
        evt.add("payload", payload);

        send(evt.toString());
    }
}
