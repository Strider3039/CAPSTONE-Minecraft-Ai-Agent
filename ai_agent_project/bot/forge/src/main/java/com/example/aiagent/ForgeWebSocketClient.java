package com.example.aiagent;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import com.google.gson.JsonObject;
import com.google.gson.JsonElement;

import java.net.URI;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class ForgeWebSocketClient extends WebSocketClient {

    // ───────────────────────────── AI toggle ─────────────────────────────
    private static volatile boolean aiEnabled = true;
    public static void setAiEnabled(boolean enabled) {
        aiEnabled = enabled;
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null) {
            mc.execute(() -> mc.player.displayClientMessage(
                net.minecraft.network.chat.Component.literal(enabled ? "§a[AI] Enabled" : "§c[AI] Disabled"),
                true
            ));
        }
        System.out.println("[WS] AI toggle -> " + (enabled ? "ENABLED" : "DISABLED"));
    }
    public static boolean isAiEnabled() { return aiEnabled; }


    private final Map<String, Long> nextAllowed = new HashMap<>();
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);
    private final AtomicLong seqCounter = new AtomicLong(0);

    private static final long ATTACK_COOLDOWN_MS = 150;
    private static final long USE_COOLDOWN_MS    = 150;
    private static final long PLACE_COOLDOWN_MS  = 150;

    private static final int MAX_INFLIGHT = 32;
    private final ArrayBlockingQueue<JsonObject> inflight = new ArrayBlockingQueue<>(MAX_INFLIGHT);

    // Latency tracking
    private final Map<String, Long> actionTimestamps = Collections.synchronizedMap(new HashMap<>());
    private final Deque<Long> latencyWindow = new ArrayDeque<>();
    private static final int LATENCY_WINDOW_SIZE = 20;

    private Runnable onReconnect = null;
    public void setOnReconnect(Runnable r) { this.onReconnect = r; }

    private long lastAckSeq = -1;
    private final AtomicBoolean bridgeReady = new AtomicBoolean(false);

    public ForgeWebSocketClient(URI serverUri) {
        super(serverUri);
    }

    // ───────────────────────────── WebSocket lifecycle ─────────────────────────────
    @Override
    public void onOpen(ServerHandshake handshakedata) {
        System.out.println("[WS] Connected to AI bridge");
        bridgeReady.set(true);
        emitBridgeHealth("info", "connected");

        // Inform Python that client is ready
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


    // ───────────────────────────── Incoming Messages ─────────────────────────────
    @Override
    public void onMessage(String message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        try {
            JsonObject json = BotMod.GSON.fromJson(message, JsonObject.class);
            if (json == null) return;

            String kind = json.has("kind") ? json.get("kind").getAsString() : "";

            // --------------------------------------------------
            // NEW EVENTS: bridge_health
            // --------------------------------------------------
            if ("bridge_health".equals(kind)) {
                System.out.println("[WS] bridge_health event: " + json.get("payload"));
                return;
            }

            // --------------------------------------------------
            // NEW EVENTS: episode_start
            // --------------------------------------------------
            if ("episode_start".equals(kind)) {
                JsonObject payload = json.getAsJsonObject("payload");
                System.out.println("[WS] Episode START: " + payload);
                return;
            }

            // --------------------------------------------------
            // NEW EVENTS: episode_end
            // --------------------------------------------------
            if ("episode_end".equals(kind)) {
                JsonObject payload = json.getAsJsonObject("payload");
                System.out.println("[WS] Episode END: " + payload);
                return;
            }

            // --------------------------------------------------
            // ACTION MESSAGES
            // --------------------------------------------------
            if ("action".equals(kind)) {

                if (!bridgeReady.get()) {
                    System.out.println("[WS] Ignoring action: bridge not ready yet");
                    return;
                }

                long seq = json.has("seq") ? json.get("seq").getAsLong() : -1;
                String actionId = json.has("action_id") ? json.get("action_id").getAsString() : "unknown";

                if (seq <= lastAckSeq) {
                    System.out.println("[WS] Ignoring stale action seq=" + seq);
                    return;
                }
                lastAckSeq = Math.max(lastAckSeq, seq);

                JsonObject payload = json.getAsJsonObject("payload");
                if (payload == null) return;

                if (!isAiEnabled()) {
                    emitActionResult(actionId, "ignored", "ai_disabled");
                    return;
                }

                if (inflight.remainingCapacity() == 0) {
                    inflight.poll();
                    emitBridgeHealth("warn", "dropped_input");
                }

                inflight.offer(payload);

                System.out.println("[WS] Action received: " + payload);

                actionTimestamps.put(actionId, System.currentTimeMillis());
                mc.execute(() -> handleStructuredAction(actionId, payload, mc));

                return;
            }

            // --------------------------------------------------
            // COMMAND MESSAGES
            // --------------------------------------------------
            if ("command".equals(kind)) {
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


            // --------------------------------------------------
            // FALLBACK: unknown message kinds
            // --------------------------------------------------
            System.out.println("[WS] Unknown message kind: " + kind);
            return;


        } catch (Exception e) {
            System.err.println("[WS] Parse error: " + e.getMessage());
        }
    }


    // ───────────────────────────── Action Handler ─────────────────────────────
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
                float dYaw = look.has("dYaw") ? look.get("dYaw").getAsFloat() : 0f;
                float dPitch = look.has("dPitch") ? look.get("dPitch").getAsFloat() : 0f;
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
                double forward = move.has("forward") ? move.get("forward").getAsDouble() : 0;
                double strafe  = move.has("strafe") ? move.get("strafe").getAsDouble() : 0;
                float moveSpeed = 0.1f;
                p.moveRelative(moveSpeed, new net.minecraft.world.phys.Vec3((float)strafe, 0.0f, (float)forward));
            } catch (Exception e) {
                overallStatus = "fail";
                reasons.add("move_error");
            }
        }

        emitActionResult(actionId, overallStatus, String.join(",", reasons));
    }


    // ───────────────────────────── Feedback Emitters ─────────────────────────────
    private void emitActionResult(String actionId, String status, String reason) {
        long now = System.currentTimeMillis();
        long latency = 0L;

        try {
            Long sentTime = actionTimestamps.remove(actionId);
            if (sentTime != null) latency = now - sentTime;
        } catch (Exception e) {
            System.err.println("[WS] Latency calc error: " + e.getMessage());
        }

        JsonObject payload = new JsonObject();
        JsonObject result = new JsonObject();

        result.addProperty("action_id", actionId);
        result.addProperty("status", status);
        if (reason != null && !reason.isEmpty()) result.addProperty("reason", reason);
        result.addProperty("server_tick",
                Minecraft.getInstance().level != null ? Minecraft.getInstance().level.getGameTime() : 0);
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

        // Show latency in-game
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            latencyWindow.addLast(latency);
            if (latencyWindow.size() > LATENCY_WINDOW_SIZE) latencyWindow.removeFirst();
            long avg = (long) latencyWindow.stream().mapToLong(Long::longValue).average().orElse(0);
            long finalLatency = latency;
            mc.execute(() -> mc.player.displayClientMessage(
                net.minecraft.network.chat.Component.literal(
                    String.format("§e[AI] Latency: %d ms (avg %d ms)", finalLatency, avg)
                ), true
            ));
        }
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
        evt.addProperty("seq", seqCounter.incrementAndGet());
        evt.addProperty("timestamp", System.currentTimeMillis() / 1000.0);
        evt.add("payload", payload);

        send(evt.toString());
    }

    private void sendBridgeReady() {
        JsonObject payload = new JsonObject();
        JsonObject ready = new JsonObject();
        ready.addProperty("level", "info");
        ready.addProperty("detail", "bridge_ready");
        payload.add("bridge_health", ready);

        JsonObject evt = new JsonObject();
        evt.addProperty("proto", "1");
        evt.addProperty("kind", "bridge_health");
        evt.addProperty("seq", seqCounter.incrementAndGet());
        evt.addProperty("timestamp", System.currentTimeMillis() / 1000.0);
        evt.add("payload", payload);

        send(evt.toString());
        System.out.println("[WS] Bridge ready for AI actions.");
    }


    // ───────────────────────────── Episode Events ─────────────────────────────
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
