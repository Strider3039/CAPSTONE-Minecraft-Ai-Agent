package com.example.aiagent.server;

import com.example.aiagent.BotMod;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerLevel;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
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

                        String kind = json.has("kind") ? json.get("kind").getAsString() : "";
                        if (!"action".equals(kind)) return;

                        JsonObject payload = json.getAsJsonObject("payload");
                        if (payload == null) return;

                        // payload is the action fields (look/move/jump/etc)
                        actionQueue.offer(payload);

                    } catch (Exception e) {
                        System.err.println("[AI-BOT][SERVER-WS] Parse error: " + e.getMessage());
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
