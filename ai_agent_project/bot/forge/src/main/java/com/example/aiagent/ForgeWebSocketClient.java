package com.example.aiagent;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import com.google.gson.JsonObject;
import com.google.gson.JsonElement;

import java.net.URI;
import java.util.*;
import java.lang.reflect.Field;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fully patched ForgeWebSocketClient
 * Fixes:
 *  - Episode never restarting
 *  - Bot not teleporting to spawn
 *  - startNewEpisode() never being invoked on episode_start
 */
public class ForgeWebSocketClient extends WebSocketClient {

    // ───────────────────────────────────────────────
    // Static AI enable toggle
    // ───────────────────────────────────────────────

    private static volatile boolean aiEnabled = true;

    public static void setAiEnabled(boolean enabled) {
        aiEnabled = enabled;
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null) {
            mc.execute(() -> mc.player.displayClientMessage(
                net.minecraft.network.chat.Component.literal(
                    enabled ? "§a[AI] Enabled" : "§c[AI] Disabled"
                ), true
            ));
        }
        System.out.println("[WS] AI toggle -> " + (enabled ? "ENABLED" : "DISABLED"));
    }

    public static boolean isAiEnabled() {
        return aiEnabled;
    }

    // ───────────────────────────────────────────────
    // Internal state
    // ───────────────────────────────────────────────

    private final Map<String, Long> nextAllowed = new HashMap<>();
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);
    private final AtomicLong seqCounter = new AtomicLong(0);

    private static final int MAX_INFLIGHT = 32;
    private final ArrayBlockingQueue<JsonObject> inflight =
        new ArrayBlockingQueue<>(MAX_INFLIGHT);

    // Latency tracking
    private final Map<String, Long> actionTimestamps =
            Collections.synchronizedMap(new HashMap<>());
    private final Deque<Long> latencyWindow = new ArrayDeque<>();
    private static final int LATENCY_WINDOW_SIZE = 20;

    private Runnable onReconnect = null;
    public void setOnReconnect(Runnable r) { this.onReconnect = r; }

    private long lastAckSeq = -1;
    private final AtomicBoolean bridgeReady = new AtomicBoolean(false);

    // ───────────────────────────────────────────────
    // Constructor
    // ───────────────────────────────────────────────

    public ForgeWebSocketClient(URI serverUri) {
        super(serverUri);
    }

    // ───────────────────────────────────────────────
    // WebSocket lifecycle
    // ───────────────────────────────────────────────

    @Override
    public void onOpen(ServerHandshake handshake) {
        System.out.println("[WS] Connected to AI bridge");
        bridgeReady.set(true);
        emitBridgeHealth("info", "connected");
        sendBridgeReady();
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        System.out.println("[WS] Connection closed: " + reason);
        bridgeReady.set(false);

        if (reconnecting.getAndSet(true)) return;

        new Thread(() -> {
            while (!isOpen()) {
                try {
                    System.out.println("[WS] Attempting reconnect...");
                    reconnectBlocking();

                    System.out.println("[WS] Reconnected successfully!");
                    inflight.clear();
                    bridgeReady.set(true);

                    emitBridgeHealth("info", "reconnected");
                    sendBridgeReady();

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

    // ───────────────────────────────────────────────
    // Message handling — **EPISODE FIX IS HERE**
    // ───────────────────────────────────────────────

    @Override
    public void onMessage(String message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        try {
            JsonObject json = BotMod.GSON.fromJson(message, JsonObject.class);
            if (json == null) return;

            String kind = json.has("kind") ? json.get("kind").getAsString() : "";

            // bridge health
            if ("bridge_health".equals(kind)) {
                System.out.println("[WS] bridge_health " + json.get("payload"));
                return;
            }

            // ───────────────────────────────────────────────
            // EPISODE START — **THE REQUIRED FIX**
            // ───────────────────────────────────────────────
            if ("episode_start".equals(kind)) {
                System.out.println("[WS] Episode START received: " + json.get("payload"));

                mc.execute(() -> {
                    BotMod inst = BotMod.getInstance();
                    if (inst != null) {
                        inst.startNewEpisode(mc);
                    } else {
                        System.err.println("[WS] ERROR: BotMod instance was null!");
                    }
                });

                return;
            }

            // EPISODE END — (server doesn't send this now but safe to handle)
            if ("episode_end".equals(kind)) {
                System.out.println("[WS] Episode END received: " + json.get("payload"));
                return;
            }

            // ───────────────────────────────────────────────
            // ACTIONS
            // ───────────────────────────────────────────────
            if ("action".equals(kind)) {

                if (!bridgeReady.get()) {
                    System.out.println("[WS] Ignoring action: bridge not ready");
                    return;
                }

                long seq = json.has("seq") ? json.get("seq").getAsLong() : -1;
                String actionId =
                        json.has("action_id") ? json.get("action_id").getAsString() : "unknown";

                if (seq <= lastAckSeq) {
                    System.out.println("[WS] Ignoring stale action seq=" + seq);
                    return;
                }
                lastAckSeq = Math.max(lastAckSeq, seq);

                JsonObject payload = json.getAsJsonObject("payload");
                if (payload == null) return;

                System.out.println("[WS] Action received: " + payload);

                if (!isAiEnabled()) {
                    emitActionResult(actionId, "ignored", "ai_disabled");
                    return;
                }

                if (inflight.remainingCapacity() == 0) {
                    inflight.poll();
                    emitBridgeHealth("warn", "dropped_input");
                }

                inflight.offer(payload);

                actionTimestamps.put(actionId, System.currentTimeMillis());
                mc.execute(() -> handleStructuredAction(actionId, payload, mc));
                return;
            }

            // COMMANDS SENT BY PYTHON
            if ("command".equals(kind)) {
                JsonObject body = json.getAsJsonObject("payload");
                if (body != null && body.has("cmd")) {
                    String cmd = body.get("cmd").getAsString();
                    mc.execute(() -> {
                        if (mc.player != null && mc.player.connection != null) {
                            mc.player.connection.sendCommand(cmd);
                            mc.player.displayClientMessage(
                                    net.minecraft.network.chat.Component.literal(
                                            "§a[AI] Executed: " + cmd),
                                    true
                            );
                        }
                    });
                }
                return;
            }

            System.out.println("[WS] Unknown kind=" + kind);

        } catch (Exception e) {
            System.err.println("[WS] Parse error: " + e.getMessage());
        }
    }

    // ───────────────────────────────────────────────
    // Action execution
    // ───────────────────────────────────────────────

    private void handleStructuredAction(String actionId, JsonObject payload, Minecraft mc) {

        if (!isAiEnabled()) {
            emitActionResult(actionId, "ignored", "ai_disabled");
            return;
        }

        LocalPlayer p = mc.player;
        if (p == null) return;

        String overallStatus = "success";
        List<String> reasons = new ArrayList<>();

        // LOOK
        if (payload.has("look")) {
            try {
                JsonObject look = payload.getAsJsonObject("look");
                float dYaw = look.get("dYaw").getAsFloat();
                float dPitch = look.get("dPitch").getAsFloat();
                p.turn(dYaw, dPitch);
            } catch (Exception e) {
                overallStatus = "fail";
                reasons.add("look_error");
            }
        }

        // MOVE
        if (payload.has("move")) {
            try {
                JsonObject move = payload.getAsJsonObject("move");
                double forward = move.get("forward").getAsDouble();
                double strafe = move.get("strafe").getAsDouble();
                float speed = 0.1f;
                p.moveRelative(speed, new net.minecraft.world.phys.Vec3(
                        (float) strafe, 0.0f, (float) forward));
            } catch (Exception e) {
                overallStatus = "fail";
                reasons.add("move_error");
            }
        }

        // JUMP
        if (payload.has("jump")) {
            try {
                boolean jump = payload.get("jump").getAsBoolean();
                if (jump && p.onGround()) {
                    p.jumpFromGround();
                }
            } catch (Exception e) {
                overallStatus = "fail";
                reasons.add("jump_error");
            }
        }

        // HOTBAR SELECT (reflection)
        if (payload.has("select_slot")) {
            try {
                int slot = payload.get("select_slot").getAsInt();
                if (slot >= 0 && slot < 9) {
                    Object inv = p.getInventory();
                    Class<?> invClass = inv.getClass();
                    Field selField = null;

                    try {
                        selField = invClass.getDeclaredField("selected");
                    } catch (NoSuchFieldException e1) {
                        try {
                            selField = invClass.getDeclaredField("selectedSlot");
                        } catch (NoSuchFieldException e2) {
                            System.err.println("[WS] Failed to locate selected slot field");
                        }
                    }

                    if (selField != null) {
                        selField.setAccessible(true);
                        selField.setInt(inv, slot);
                    }
                }
            } catch (Exception e) {
                overallStatus = "fail";
                reasons.add("slot_error");
            }
        }

        emitActionResult(actionId, overallStatus, String.join(",", reasons));
    }

    // ───────────────────────────────────────────────
    // Event emitters
    // ───────────────────────────────────────────────

    private void emitActionResult(String actionId, String status, String reason) {
        long now = System.currentTimeMillis();
        long latency = 0L;

        try {
            Long sent = actionTimestamps.remove(actionId);
            if (sent != null) latency = now - sent;
        } catch (Exception ignored) {}

        JsonObject payload = new JsonObject();
        JsonObject result = new JsonObject();
        result.addProperty("action_id", actionId);
        result.addProperty("status", status);
        if (!reason.isEmpty()) result.addProperty("reason", reason);
        result.addProperty("server_tick",
                Minecraft.getInstance().level != null ?
                        Minecraft.getInstance().level.getGameTime() : 0);
        result.addProperty("ts_server", now / 1000.0);
        result.addProperty("latency_ms", latency);

        payload.add("action_result", result);

        JsonObject evt = new JsonObject();
        evt.addProperty("proto", "1");
        evt.addProperty("kind", "action_result");
        evt.addProperty("seq", seqCounter.incrementAndGet());
        evt.addProperty("timestamp", now / 1000.0);
        evt.add("payload", payload);

        send(evt.toString());
    }

    private void emitBridgeHealth(String level, String detail) {
        JsonObject payload = new JsonObject();
        JsonObject body = new JsonObject();
        body.addProperty("level", level);
        body.addProperty("detail", detail);
        payload.add("bridge_health", body);

        JsonObject evt = new JsonObject();
        evt.addProperty("proto", "1");
        evt.addProperty("kind", "bridge_health");
        evt.addProperty("seq", seqCounter.incrementAndGet());
        evt.addProperty("timestamp", System.currentTimeMillis() / 1000.0);
        evt.add("payload", payload);

        send(evt.toString());
    }

    private void sendBridgeReady() {
        JsonObject payload = new JsonObject();
        JsonObject body = new JsonObject();
        body.addProperty("level", "info");
        body.addProperty("detail", "bridge_ready");
        payload.add("bridge_health", body);

        JsonObject evt = new JsonObject();
        evt.addProperty("proto", "1");
        evt.addProperty("kind", "bridge_health");
        evt.addProperty("seq", seqCounter.incrementAndGet());
        evt.addProperty("timestamp", System.currentTimeMillis() / 1000.0);
        evt.add("payload", payload);

        send(evt.toString());
        System.out.println("[WS] Bridge ready.");
    }

    // ───────────────────────────────────────────────
    // Episode emitters
    // ───────────────────────────────────────────────

    public void emitEpisodeEnd(String reason) {
        JsonObject payload = new JsonObject();
        JsonObject body = new JsonObject();
        body.addProperty("reason", reason);
        payload.add("episode_end", body);

        JsonObject evt = new JsonObject();
        evt.addProperty("proto", "1");
        evt.addProperty("kind", "episode_end");
        evt.addProperty("seq", seqCounter.incrementAndGet());
        evt.addProperty("timestamp", System.currentTimeMillis() / 1000.0);
        evt.add("payload", payload);

        send(evt.toString());
    }

    public void emitEpisodeStart() {
        JsonObject payload = new JsonObject();
        JsonObject body = new JsonObject();
        body.addProperty("reason", "start");
        payload.add("episode_start", body);

        JsonObject evt = new JsonObject();
        evt.addProperty("proto", "1");
        evt.addProperty("kind", "episode_start");
        evt.addProperty("seq", seqCounter.incrementAndGet());
        evt.addProperty("timestamp", System.currentTimeMillis() / 1000.0);
        evt.add("payload", payload);

        send(evt.toString());
    }
}
