package com.example.aiagent.server;

import com.example.aiagent.BotMod;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerLevel;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class ServerBridgeWebSocketClient {

    private final String uri;
    private volatile WebSocketClient client;
    private volatile long nextAttemptMs = 0;
    private final AtomicBoolean connecting = new AtomicBoolean(false);

    // WS thread -> server thread queue
    private final ConcurrentLinkedQueue<JsonObject> actionQueue = new ConcurrentLinkedQueue<>();

    public ServerBridgeWebSocketClient(String uri) {
        this.uri = uri;
    }

    public void ensureConnected() {
        long now = System.currentTimeMillis();

        if (client != null && client.isOpen()) return;
        if (now < nextAttemptMs) return;
        nextAttemptMs = now + 3000;

        if (connecting.getAndSet(true)) return;

        try {
            WebSocketClient c = new WebSocketClient(new URI(uri)) {

                @Override
                public void onOpen(ServerHandshake handshakedata) {
                    System.out.println("[AI-BOT][SERVER-WS] Connected to " + uri);
                    connecting.set(false);
                }

                @Override
                public void onMessage(String message) {
                    try {
                        JsonObject json = BotMod.GSON.fromJson(message, JsonObject.class);
                        if (json == null) return;

                        // ---- Schema-aligned: expect Action v1 only
                        String proto = json.has("proto") ? json.get("proto").getAsString() : "";
                        String kind  = json.has("kind")  ? json.get("kind").getAsString()  : "";
                        if (!"1".equals(proto)) return;
                        if (!"action".equals(kind)) return;

                        if (!json.has("payload")) return;
                        JsonObject actionPayload = json.getAsJsonObject("payload");
                        if (actionPayload == null) return;

                        // Required by your schema
                        int seq = json.has("seq") ? json.get("seq").getAsInt() : -1;
                        String actionId = json.has("action_id") ? json.get("action_id").getAsString() : null;
                        if (seq < 0 || actionId == null || actionId.isBlank()) return;

                        // Optional "deadline_ms" controls how long to hold this action
                        int deadlineMs = json.has("deadline_ms") ? json.get("deadline_ms").getAsInt() : 50;
                        if (deadlineMs <= 0) deadlineMs = 50;

                        // Convert ms -> ticks (20 TPS => 50ms per tick)
                        int ticks = Math.max(1, (int) Math.round(deadlineMs / 50.0));

                        // ---- Wrap into INTERNAL step request for FakeBotManager
                        // This is not a wire schema; it's internal.
                        JsonObject step = new JsonObject();
                        step.addProperty("cmd", "step");           // internal
                        step.addProperty("ticks", ticks);          // internal
                        step.addProperty("seq", seq);              // internal (for correlation)
                        step.addProperty("action_id", actionId);   // internal (for correlation)
                        step.add("action", actionPayload);         // internal

                        actionQueue.offer(step);

                    } catch (Exception e) {
                        System.err.println("[AI-BOT][SERVER-WS] Parse error: " + e.getMessage());
                    }
                }

                @Override
                public void onMessage(ByteBuffer bytes) {
                    try {
                        byte[] arr = new byte[bytes.remaining()];
                        bytes.get(arr);
                        onMessage(new String(arr, StandardCharsets.UTF_8)); // reuse text path
                    } catch (Exception e) {
                        System.err.println("[AI-BOT][SERVER-WS] Binary parse error: " + e.getMessage());
                    }
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    System.out.println("[AI-BOT][SERVER-WS] Closed: " + reason);
                    connecting.set(false);
                }

                @Override
                public void onError(Exception ex) {
                    System.err.println("[AI-BOT][SERVER-WS] Error: " + ex.getMessage());
                    connecting.set(false);
                }
            };

            client = c;

            new Thread(() -> {
                try {
                    c.connectBlocking();
                } catch (Exception e) {
                    System.err.println("[AI-BOT][SERVER-WS] connectBlocking failed: " + e.getMessage());
                    connecting.set(false);
                }
            }, "AI-BOT-ServerWS").start();

        } catch (Exception e) {
            System.err.println("[AI-BOT][SERVER-WS] setup failed: " + e.getMessage());
            connecting.set(false);
        }
    }

    /**
     * Drain WS actions into FakeBotManager queue (server thread).
     * This matches: ws.drainActionsAndApply(level, BOTS);
     */
    public void drainActionsAndApply(ServerLevel level, FakeBotManager bots) {
        ensureConnected();
        if (bots == null) return;

        JsonObject payload;
        while ((payload = actionQueue.poll()) != null) {
            bots.enqueueActionJson(BotMod.GSON.toJson(payload));
        }
    }

    public void drainCompletedResultsAndSend(FakeBotManager bots) {
        JsonObject msg;
        while ((msg = bots.pollCompletedResult()) != null) {
            sendJson(msg); // MUST send observation + action_result
        }
    }

    public void sendJson(JsonObject msg) {
        WebSocketClient c = this.client;
        if (c == null || !c.isOpen()) return;
        c.send(msg.toString());

        String kind = msg.has("kind") ? msg.get("kind").getAsString() : "<no-kind>";
        System.out.println("[WS OUT] " + kind);

    }



    // Optional compatibility overload (if you still call old signature somewhere)
    public void drainActionsAndApply(ServerLevel level) {
        // No-op: kept only so old code compiles if it still exists somewhere.
        // Prefer the (ServerLevel, FakeBotManager) method.
        ensureConnected();
        actionQueue.clear();
    }

    public void closeBlockingSafe() {
        try {
            WebSocketClient c = client;
            client = null;
            if (c != null) c.closeBlocking();
        } catch (Exception ignored) {}
    }
}
