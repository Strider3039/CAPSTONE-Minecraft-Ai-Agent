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

    // Debug switch
    private static final boolean DEBUG_WS = false;
    private static final boolean DEBUG_MOVE = false;

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

                    // NEW: identify this websocket as the SERVER
                    JsonObject hello = new JsonObject();
                    hello.addProperty("proto", "1");
                    hello.addProperty("kind", "hello");
                    hello.addProperty("role", "server");
                    send(hello.toString());
                }

                @Override
                public void onMessage(String message) {
                    if (DEBUG_MOVE) System.out.println("[RAW WS MESSAGE] " + message);
                    try {
                        JsonObject json = BotMod.GSON.fromJson(message, JsonObject.class);
                        if (json == null) return;

                        String proto = json.has("proto") ? json.get("proto").getAsString() : "";
                        String kind  = json.has("kind")  ? json.get("kind").getAsString()  : "";
                        if (!"1".equals(proto) || !"action".equals(kind)) return;

                        if (!json.has("payload") || !json.get("payload").isJsonObject()) {
                            if (DEBUG_WS) System.out.println("[AI-BOT][SERVER-WS] Dropped action (missing payload): " + message);
                            return;
                        }
                        JsonObject actionPayload = json.getAsJsonObject("payload");

                        int seq = json.has("seq") ? json.get("seq").getAsInt() : -1;
                        String actionId = json.has("action_id") ? json.get("action_id").getAsString() : null;

                        if (seq < 0 || actionId == null || actionId.isBlank()) {
                            if (DEBUG_WS) System.out.println("[AI-BOT][SERVER-WS] Dropped action (missing seq/action_id): " + message);
                            return;
                        }

                        if (DEBUG_MOVE) {
                            System.out.println("[WS IN] seq=" + seq + " action_id=" + actionId
                                + " payloadKeys=" + actionPayload.keySet()
                                + " hasMove=" + actionPayload.has("move"));
                            if (actionPayload.has("move")) {
                                JsonObject mv = actionPayload.getAsJsonObject("move");
                                System.out.println("[WS IN] move forward="
                                    + (mv.has("forward") ? mv.get("forward").getAsDouble() : null)
                                    + " strafe="
                                    + (mv.has("strafe") ? mv.get("strafe").getAsDouble() : null));
                            }
                        }

                        int deadlineMs = json.has("deadline_ms") ? json.get("deadline_ms").getAsInt() : 50;
                        if (deadlineMs <= 0) deadlineMs = 50;

                        int ticks = Math.max(1, (int) Math.ceil(deadlineMs / 50.0));

                        // if ("move".equals(actionId)) ticks = 10;

                        JsonObject step = new JsonObject();
                        step.addProperty("cmd", "step");
                        step.addProperty("ticks", ticks);
                        step.addProperty("seq", seq);
                        step.addProperty("action_id", actionId);
                        step.add("action", actionPayload);

                        actionQueue.offer(step);
                        if (DEBUG_WS) System.out.println("[WS RX ACTION] queued step seq=" + seq + " action_id=" + actionId
                            + " keys=" + actionPayload.keySet());

                        if (DEBUG_WS) System.out.println("[AI-BOT][SERVER-WS] Enqueued action seq=" + seq + " id=" + actionId + " ticks=" + ticks);

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
                    System.out.println("[WS CLOSED] code=" + code + " reason=" + reason);
                    connecting.set(false);
                }

                @Override
                public void onError(Exception ex) {
                    System.out.println("[WS ERROR] " + ex.getMessage());
                    connecting.set(false);
                }
            };

            client = c;
            client.setConnectionLostTimeout(0); // detect dead connections faster

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

            if (DEBUG_MOVE) System.out.println("[WS->BOT] enqueue stepJson=" + payload.toString());

            bots.enqueueActionJson(BotMod.GSON.toJson(payload));
        }
    }

    public void drainCompletedResultsAndSend(FakeBotManager bots) {
        if (bots == null) return;

        JsonObject msg;
        while ((msg = bots.pollCompletedResult()) != null) {
            String kind = msg.has("kind") ? msg.get("kind").getAsString() : "unknown";
            int seq = msg.has("seq") ? msg.get("seq").getAsInt() : -1;

            if (DEBUG_WS) System.out.println("[WS TX] kind=" + kind + " seq=" + seq);

            sendJson(msg);
        }
    }


    private void sendJson(JsonObject msg) {
        String s = msg.toString();
        String kind = msg.has("kind") ? msg.get("kind").getAsString() : "<?>";
        int seq = msg.has("seq") ? msg.get("seq").getAsInt() : -999;

        if (client == null || !client.isOpen()) {
            System.out.println("[AI-BOT][SERVER-WS] sendJson SKIP (socket not open) kind=" + kind + " seq=" + seq);
            return;
        }
        if (DEBUG_WS) System.out.println("[AI-BOT][SERVER-WS] OUT " + s);
        if (DEBUG_WS) System.out.println("[AI-BOT][SERVER-WS] sendJson TEXT kind=" + kind + " seq=" + seq + " bytes=" + s.length());

        client.send(s);
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
