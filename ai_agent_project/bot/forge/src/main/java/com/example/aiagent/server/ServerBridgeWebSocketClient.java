package com.example.aiagent.server;

import com.example.aiagent.BotMod;
import com.example.aiagent.BridgeConstants;
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

    private volatile String uri;
    private volatile WebSocketClient client;
    private volatile long nextAttemptMs = 0;
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    /** When false, {@link #ensureConnected()} does nothing (e.g. bridge in PLAYER mode — client owns the socket). */
    private final AtomicBoolean autoConnect = new AtomicBoolean(true);

    /** Last printed {@link #ensureConnected()} skip summary (avoid tick spam; never include changing ms). */
    private volatile String lastEnsureDebugSummary = "";

    /** Throttle noisy sendJson SKIP logs when the socket is down (tick spam). */
    private volatile long lastSendJsonSkipLogMs = 0;
    private volatile int sendJsonSkipSuppressedCount = 0;

    // Debug switch
    private static final boolean DEBUG_WS = false;
    private static final boolean DEBUG_MOVE = false;

    // WS thread -> server thread queue
    private final ConcurrentLinkedQueue<JsonObject> actionQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<JsonObject> eventQueue = new ConcurrentLinkedQueue<>();

    public ServerBridgeWebSocketClient(String uri) {
        this.uri = uri;
    }

    public void setAutoConnect(boolean enabled) {
        boolean was = autoConnect.getAndSet(enabled);
        if (was != enabled) {
            System.out.println("[AI-BOT][DEBUG][SERVER-WS] setAutoConnect " + was + " -> " + enabled + " uri=" + uri);
        }
        if (enabled) {
            nextAttemptMs = 0;
        }
    }

    public boolean isAutoConnectEnabled() {
        return autoConnect.get();
    }

    /**
     * Stop reconnecting and close the server-side bridge socket. Call when the runtime switches to
     * PLAYER so the Python bridge is free for the game client WebSocket.
     */
    public void pauseForPlayerMode() {
        System.out.println("[AI-BOT][DEBUG][SERVER-WS] pauseForPlayerMode: closing socket, autoConnect=false");
        setAutoConnect(false);
        closeBlockingSafe();
        lastEnsureDebugSummary = "";
        System.out.println("[AI-BOT][SERVER-WS] Paused (PLAYER mode or idle); server will not reconnect until SERVER_BOT / player joins.");
    }

    public void ensureConnected() {
        if (!autoConnect.get()) {
            String s = "skip|!autoConnect uri=" + uri;
            if (!s.equals(lastEnsureDebugSummary)) {
                lastEnsureDebugSummary = s;
                System.out.println("[AI-BOT][DEBUG][SERVER-WS] ensureConnected: " + s);
            }
            return;
        }

        long now = System.currentTimeMillis();

        if (client != null && client.isOpen()) {
            String s = "ok|open uri=" + uri;
            if (!s.equals(lastEnsureDebugSummary)) {
                lastEnsureDebugSummary = s;
                System.out.println("[AI-BOT][DEBUG][SERVER-WS] ensureConnected: " + s);
            }
            return;
        }
        if (now < nextAttemptMs) {
            // Do not include remaining ms in dedup key — it changes every tick and floods the log.
            String s = "skip|backoff uri=" + uri;
            if (!s.equals(lastEnsureDebugSummary)) {
                lastEnsureDebugSummary = s;
                long waitMs = nextAttemptMs - now;
                System.out.println("[AI-BOT][DEBUG][SERVER-WS] ensureConnected: " + s
                        + " (~" + waitMs + "ms until reconnect attempt)");
            }
            return;
        }
        nextAttemptMs = now + 3000;

        if (connecting.getAndSet(true)) {
            String s = "skip|already connecting uri=" + uri;
            if (!s.equals(lastEnsureDebugSummary)) {
                lastEnsureDebugSummary = s;
                System.out.println("[AI-BOT][DEBUG][SERVER-WS] ensureConnected: " + s);
            }
            return;
        }

        lastEnsureDebugSummary = "connect|starting uri=" + uri;
        System.out.println("[AI-BOT][DEBUG][SERVER-WS] ensureConnected: " + lastEnsureDebugSummary);

        try {
            WebSocketClient c = new WebSocketClient(new URI(uri)) {
                

                @Override
                public void onOpen(ServerHandshake handshakedata) {
                    System.out.println("[AI-BOT][SERVER-WS] Connected to " + uri);
                    connecting.set(false);
                    lastEnsureDebugSummary = "ok|open uri=" + uri;

                    // Identify as SERVER (must match Python BridgeConstants)
                    JsonObject hello = new JsonObject();
                    hello.addProperty("proto", "1");
                    hello.addProperty("kind", "hello");
                    hello.addProperty("role", BridgeConstants.ROLE_SERVER);
                    hello.addProperty("control_mode", BridgeConstants.MODE_SERVER_BOT);
                    send(hello.toString());
                    System.out.println("[AI-BOT][DEBUG][SERVER-WS] hello sent role=" + BridgeConstants.ROLE_SERVER
                            + " control_mode=" + BridgeConstants.MODE_SERVER_BOT);
                }

                @Override
                public void onMessage(String message) {
                    if (DEBUG_MOVE) System.out.println("[RAW WS MESSAGE] " + message);
                    try {
                        JsonObject json = BotMod.GSON.fromJson(message, JsonObject.class);
                        if (json == null) return;

                        String proto = json.has("proto") ? json.get("proto").getAsString() : "";
                        String kind  = json.has("kind")  ? json.get("kind").getAsString()  : "";
                        if (!"1".equals(proto)) return;

                        if ("episode_start".equals(kind) || "bridge_health".equals(kind)) {
                            eventQueue.offer(json);
                            return;
                        }

                        if (!"action".equals(kind)) return;

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

    public boolean isConnected() {
        return client != null && client.isOpen();
    }

    public String getUri() {
        return uri;
    }

    /**
     * Repoint this server-side bridge connection at a new address (e.g. set from a client's
     * in-game config screen via {@code C2SRuntimeConfigPacket}) and reconnect immediately.
     * No-op if the address is blank or unchanged.
     */
    public void updateBridgeUri(String newUri) {
        String normalized = (newUri == null) ? null : newUri.trim();
        if (normalized == null || normalized.isEmpty() || normalized.equals(this.uri)) {
            return;
        }
        System.out.println("[AI-BOT][SERVER-WS] Bridge URI changed " + this.uri + " -> " + normalized + "; reconnecting.");
        this.uri = normalized;
        closeBlockingSafe();
        nextAttemptMs = 0;
        lastEnsureDebugSummary = "";
        ensureConnected();
    }

    /**
     * Drain WS actions into FakeBotManager queue (server thread).
     * This matches: ws.drainActionsAndApply(level, BOTS);
     */
    public void drainActionsAndApply(ServerLevel level, FakeBotManager bots) {
        ensureConnected();
        if (bots == null) return;

        JsonObject evt;
        while ((evt = eventQueue.poll()) != null) {
            String kind = evt.has("kind") ? evt.get("kind").getAsString() : "";
            if ("episode_start".equals(kind)) {
                JsonObject payload = evt.has("payload") && evt.get("payload").isJsonObject()
                        ? evt.getAsJsonObject("payload")
                        : new JsonObject();
                JsonObject body = payload.has("episode_start") && payload.get("episode_start").isJsonObject()
                        ? payload.getAsJsonObject("episode_start")
                        : new JsonObject();
                String reason = body.has("reason") ? body.get("reason").getAsString() : "episode_start";
                bots.startNewEpisode(level, reason);

                BotMod inst = BotMod.getInstance();
                if (inst != null && inst.getSoakController() != null) {
                    inst.getSoakController().onEpisodeStarted(level, reason);
                }
                continue;
            }

            if ("bridge_health".equals(kind) && DEBUG_WS) {
                System.out.println("[AI-BOT][SERVER-WS] bridge_health " + evt);
            }
        }

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
            long t = System.currentTimeMillis();
            if (t - lastSendJsonSkipLogMs >= 5000) {
                if (sendJsonSkipSuppressedCount > 0) {
                    System.out.println("[AI-BOT][SERVER-WS] sendJson SKIP (socket not open) ... suppressed "
                            + sendJsonSkipSuppressedCount + " similar");
                    sendJsonSkipSuppressedCount = 0;
                }
                System.out.println("[AI-BOT][SERVER-WS] sendJson SKIP (socket not open) kind=" + kind + " seq=" + seq);
                lastSendJsonSkipLogMs = t;
            } else {
                sendJsonSkipSuppressedCount++;
            }
            return;
        }
        if (DEBUG_WS) System.out.println("[AI-BOT][SERVER-WS] OUT " + s);
        if (DEBUG_WS) System.out.println("[AI-BOT][SERVER-WS] sendJson TEXT kind=" + kind + " seq=" + seq + " bytes=" + s.length());

        client.send(s);
    }

    /**
     * Send a config_update message to the bridge for hot-reload (DQN/reward params, etc.).
     * Payload should be the runtime overlay (e.g. control_mode, policy.reward, policy.dqn).
     */
    public void sendConfigUpdate(JsonObject payload) {
        if (payload == null) {
            System.out.println("[AI-BOT][DEBUG][SERVER-WS] sendConfigUpdate SKIP: payload null");
            return;
        }
        String cm = payload.has("control_mode") ? payload.get("control_mode").getAsString() : "?";
        boolean open = client != null && client.isOpen();
        System.out.println("[AI-BOT][DEBUG][SERVER-WS] sendConfigUpdate control_mode=" + cm
                + " socketOpen=" + open + " autoConnect=" + autoConnect.get());
        JsonObject msg = new JsonObject();
        msg.addProperty("proto", "1");
        msg.addProperty("kind", "config_update");
        msg.add("payload", payload);
        sendJson(msg);
    }

    public void sendEvalControlStartEpisode() {
        JsonObject msg = new JsonObject();
        msg.addProperty("proto", "1");
        msg.addProperty("kind", "eval_control");

        JsonObject payload = new JsonObject();
        payload.addProperty("action", "start_episode");
        msg.add("payload", payload);
        sendJson(msg);
    }

    public void sendEpisodeEnd(String reason) {
        JsonObject msg = new JsonObject();
        msg.addProperty("proto", "1");
        msg.addProperty("kind", "episode_end");

        JsonObject payload = new JsonObject();
        JsonObject body = new JsonObject();
        body.addProperty("reason", (reason == null || reason.isBlank()) ? "unknown" : reason);
        payload.add("episode_end", body);
        msg.add("payload", payload);
        sendJson(msg);
    }




    // Optional compatibility overload (if you still call old signature somewhere)
    public void drainActionsAndApply(ServerLevel level) {
        // No-op: kept only so old code compiles if it still exists somewhere.
        // Prefer the (ServerLevel, FakeBotManager) method.
        ensureConnected();
        actionQueue.clear();
        eventQueue.clear();
    }

    public void closeBlockingSafe() {
        try {
            WebSocketClient c = client;
            client = null;
            if (c != null) c.closeBlocking();
        } catch (Exception ignored) {}
    }
}
