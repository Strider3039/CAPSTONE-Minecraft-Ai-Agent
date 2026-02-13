package com.example.aiagent.client;

import com.example.aiagent.BotMod;
import com.example.aiagent.net.BotNet;
import com.example.aiagent.net.C2SBotActionPacket;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ForgeWebSocketClient (CLIENT ONLY)
 *
 * IMPORTANT DESIGN CHANGE:
 * - This class NO LONGER spawns its own reconnect thread.
 * - Reconnect logic must be owned by ClientBridgeHooks.ensureBridgeConnected().
 *
 * Modes:
 *  - PLAYER: execute actions locally
 *  - SERVER_BOT: forward action payload to server via Forge packet
 */
public class ForgeWebSocketClient extends WebSocketClient {

    // ───────────────────────────────────────────────
    // Control mode (PLAYER vs SERVER_BOT)
    // ───────────────────────────────────────────────

    public enum ControlMode {
        PLAYER,
        SERVER_BOT
    }

    private static volatile ControlMode controlMode = ControlMode.PLAYER;

    public static void setControlMode(ControlMode mode) {
        if (mode == null) mode = ControlMode.PLAYER;
        controlMode = mode;

        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            ControlMode finalMode = mode;
            mc.execute(() -> {
                if (mc.player != null) {
                    mc.player.displayClientMessage(
                            Component.literal(finalMode == ControlMode.PLAYER
                                    ? "§a[AI] Control Mode: PLAYER"
                                    : "§b[AI] Control Mode: SERVER_BOT"),
                            true
                    );
                }
                // Always release local inputs when leaving PLAYER control
                if (finalMode != ControlMode.PLAYER) {
                    releaseAllKeys(mc);
                }
            });
        }
        System.out.println("[WS] ControlMode -> " + controlMode);
    }

    public static ControlMode getControlMode() {
        return controlMode;
    }

    // ───────────────────────────────────────────────
    // AI enable toggle (hard kill switch)
    // ───────────────────────────────────────────────

    private static volatile boolean aiEnabled = true;

    public static void setAiEnabled(boolean enabled) {
        aiEnabled = enabled;

        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            mc.execute(() -> {
                if (mc.player != null) {
                    mc.player.displayClientMessage(
                            Component.literal(enabled ? "§a[AI] Enabled" : "§c[AI] Disabled"),
                            true
                    );
                }
                if (!enabled) releaseAllKeys(mc);
            });
        }
        System.out.println("[WS] AI toggle -> " + (enabled ? "ENABLED" : "DISABLED"));
    }

    public static boolean isAiEnabled() {
        return aiEnabled;
    }

    private static void releaseAllKeys(Minecraft mc) {
        if (mc == null) return;
        Options opt = mc.options;
        if (opt == null) return;

        opt.keyUp.setDown(false);
        opt.keyDown.setDown(false);
        opt.keyLeft.setDown(false);
        opt.keyRight.setDown(false);
        opt.keyJump.setDown(false);
        opt.keyShift.setDown(false);
        opt.keySprint.setDown(false);
        opt.keyAttack.setDown(false);
        opt.keyUse.setDown(false);
    }

    // ───────────────────────────────────────────────
    // Internal state
    // ───────────────────────────────────────────────

    private final AtomicLong seqCounter = new AtomicLong(0);

    private static final int MAX_INFLIGHT = 32;
    private final ArrayBlockingQueue<JsonObject> inflight = new ArrayBlockingQueue<>(MAX_INFLIGHT);

    private final Map<String, Long> actionTimestamps =
            Collections.synchronizedMap(new HashMap<>());

    private Runnable onReconnect = null;
    public void setOnReconnect(Runnable r) { this.onReconnect = r; }

    private long lastAckSeq = -1;
    private final AtomicBoolean bridgeReady = new AtomicBoolean(false);

    // Edge tracking for click-like behavior in PLAYER mode
    private boolean lastAttackDown = false;
    private boolean lastUseDown = false;

    // Look limits to avoid snapping (PLAYER mode)
    private static final float MAX_YAW_PER_TICK = 15.0f;
    private static final float MAX_PITCH_PER_TICK = 10.0f;

    public ForgeWebSocketClient(URI serverUri) {
        super(serverUri);

        // Java-WebSocket keepalive:
        // If you set this too low it will false-timeout under lag.
        // 30s is a good dev default.
        setConnectionLostTimeout(30);
    }

    // ───────────────────────────────────────────────
    // WebSocket lifecycle
    // ───────────────────────────────────────────────

    @Override
    public void onOpen(ServerHandshake handshake) {

        // NEW: If we're in multiplayer / dedicated context, client WS must not be active.
        if (!shouldClientWebSocketBeEnabled()) {
            System.out.println("[WS] Connected but CLIENT WS is disabled in this mode. Closing.");
            try { close(); } catch (Exception ignored) {}
            return;
        }
        System.out.println("[WS] Connected to AI bridge");
        bridgeReady.set(true);

        // IMPORTANT: reset stale sequence tracking on a new connection
        lastAckSeq = -1;
        inflight.clear();

        // NEW: identify this websocket as the CLIENT
        JsonObject hello = new JsonObject();
        hello.addProperty("proto", "1");
        hello.addProperty("kind", "hello");
        hello.addProperty("role", "client");
        send(hello.toString());

        emitBridgeHealth("info", "connected");
        sendBridgeReady();

        // notify hook layer
        if (onReconnect != null) onReconnect.run();
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        System.out.println("[WS] Connection closed: " + reason + " (code=" + code + ", remote=" + remote + ")");
        bridgeReady.set(false);

        Minecraft mc = Minecraft.getInstance();
        if (mc != null) mc.execute(() -> releaseAllKeys(mc));

        // NO reconnect loop here anymore.
        // ClientBridgeHooks.ensureBridgeConnected() must create a fresh client instance.
    }

    @Override
    public void onError(Exception ex) {
        System.err.println("[WS ERROR] " + ex);
    }

    // ───────────────────────────────────────────────
    // Message handling
    // ───────────────────────────────────────────────

    @Override
    public void onMessage(String message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;

        try {
            JsonObject json = BotMod.GSON.fromJson(message, JsonObject.class);
            if (json == null) return;

            String kind = json.has("kind") ? json.get("kind").getAsString() : "";

            if ("bridge_health".equals(kind)) {
                System.out.println("[WS] bridge_health " + json.get("payload"));
                return;
            }

            if ("episode_start".equals(kind)) {
                System.out.println("[WS] Episode START received: " + json.get("payload"));
                mc.execute(() -> EpisodeController.startNewEpisode(mc));
                return;
            }

            if ("episode_end".equals(kind)) {
                System.out.println("[WS] Episode END received: " + json.get("payload"));
                mc.execute(() -> {
                    BotMod inst = BotMod.getInstance();
                    if (inst != null) inst.markEpisodeEnded("remote_episode_end");
                });
                return;
            }

            if (!"action".equals(kind)) {
                return;
            }

            if (!bridgeReady.get()) {
                System.out.println("[WS] Ignoring action: bridge not ready");
                return;
            }

            int seq = json.has("seq") ? json.get("seq").getAsInt() : -1;
            String actionId = json.has("action_id") ? json.get("action_id").getAsString() : "unknown";

            // Stale protection: drops replays/out-of-order actions
            if (seq <= lastAckSeq) {
                System.out.println("[WS] Ignoring stale action seq=" + seq + " last=" + lastAckSeq);
                return;
            }
            lastAckSeq = seq;

            JsonObject payload = json.getAsJsonObject("payload");
            boolean needsAck = actionNeedsAck(json, payload);

            // NEW: refuse control if client WS is disabled in this environment
            if (!shouldClientWebSocketBeEnabled()) {
                System.out.println("[WS] Dropping action: client WS disabled in multiplayer/dedicated. seq=" + seq);
                if (needsAck) {
                    emitActionResult(seq, actionId, "fail", "client_ws_disabled_in_multiplayer");
                }
                return;
            }

            if (payload == null) return;

            if (!isAiEnabled()) {
                emitActionResult(seq, actionId, "blocked", "ai_disabled");
                return;
            }

            if (inflight.remainingCapacity() == 0) {
                inflight.poll();
                emitBridgeHealth("warn", "dropped_input");
            }

            inflight.offer(payload);
            actionTimestamps.put(actionId, System.currentTimeMillis());

            // Route based on mode
            ControlMode mode = getControlMode();
            if (mode == ControlMode.SERVER_BOT) {
                mc.execute(() -> {
                    try {
                        releaseAllKeys(mc);
                        int deadlineMs = json.has("deadline_ms") ? json.get("deadline_ms").getAsInt() : 50;
                        forwardActionToServer(seq, actionId, deadlineMs, payload);

                        // IMPORTANT:
                        // Do NOT emit action_result on success in SERVER_BOT mode.
                        // The dedicated server mod will emit the real action_result for the seq.
                    } catch (Exception e) {
                        // If forwarding fails, respond so Python doesn't hang.
                        if (needsAck) {
                            emitActionResult(seq, actionId, "fail", "forward_exception");
                        }
                        System.err.println("[WS] forwardActionToServer error: " + e.getMessage());
                    }
                });
            } else {
                mc.execute(() -> {
                    try {
                        handleStructuredAction(seq, actionId, payload, mc);

                        // ACK discrete actions to prevent Python timeouts
                        if (needsAck) {
                            emitActionResult(seq, actionId, "success", "client_player");
                        }
                    } catch (Exception e) {
                        if (needsAck) {
                            emitActionResult(seq, actionId, "fail", "client_exception");
                        }
                        System.err.println("[WS] handleStructuredAction error: " + e.getMessage());
                    }
                });
            }

        } catch (Exception e) {
            System.err.println("[WS] Parse error: " + e.getMessage());
        }
    }

    private static boolean shouldClientWebSocketBeEnabled() {
        Minecraft mc = Minecraft.getInstance();
        return mc.hasSingleplayerServer();
    }

    private boolean canClientSendToBridge() {
        return shouldClientWebSocketBeEnabled() && bridgeReady.get() && this.isOpen();
    }

    // ───────────────────────────────────────────────
    // Mode B: forward payload to server via Forge packet
    // ───────────────────────────────────────────────

    private void forwardActionToServer(int seq, String actionId, int deadlineMs, JsonObject payload) {
        if (payload == null) return;
        if (actionId == null || actionId.isBlank())
            actionId = "unknown";
        if (deadlineMs <= 0)
            deadlineMs = 50;

        JsonObject env = new JsonObject();
        env.addProperty("proto", "1");
        env.addProperty("kind", "action");
        env.addProperty("seq", seq);
        env.addProperty("action_id", actionId);
        env.addProperty("deadline_ms", deadlineMs);
        env.add("payload", payload);

        BotNet.CHANNEL.sendToServer(new C2SBotActionPacket(env.toString()));
    }

    private static boolean actionNeedsAck(JsonObject json, JsonObject payload) {
        // Prefer explicit await_result if present
        if (json.has("await_result") && json.get("await_result").isJsonPrimitive()) {
            try {
                return json.get("await_result").getAsBoolean();
            } catch (Exception ignored) {
            }
        }
        // Fallback: infer discrete actions from payload keys
        if (payload == null)
            return false;
        return payload.has("select_slot") || payload.has("attack") || payload.has("use");
    }

    // Use ONLY schema-allowed statuses
    private static String normalizeStatus(String s) {
        if (s == null)
            return "fail";
        return switch (s) {
            case "success", "fail", "cooldown", "blocked", "timeout" -> s;
            default -> "success"; // treat "forwarded"/"ignored" as success in client bridge
        };
    }


    // ───────────────────────────────────────────────
    // Mode A: local execution on LocalPlayer
    // ───────────────────────────────────────────────

    private void handleStructuredAction(int actionSeq, String actionId, JsonObject payload, Minecraft mc) {

        if (!isAiEnabled()) {
            releaseAllKeys(mc);
            emitActionResult(actionSeq, actionId, "blocked", "ai_disabled");
            return;
        }

        LocalPlayer p = mc.player;
        if (p == null) {
            emitActionResult(actionSeq, actionId, "fail", "no_player");
            return;
        }

        String status = "success";
        String reason = "";

        try {
            // 1) LOOK (clamped deltas)
            if (payload.has("look")) {
                JsonObject look = payload.getAsJsonObject("look");
                float dYaw = look.has("dYaw") ? look.get("dYaw").getAsFloat() : 0f;
                float dPitch = look.has("dPitch") ? look.get("dPitch").getAsFloat() : 0f;

                dYaw = clamp(dYaw, -MAX_YAW_PER_TICK, MAX_YAW_PER_TICK);
                dPitch = clamp(dPitch, -MAX_PITCH_PER_TICK, MAX_PITCH_PER_TICK);

                float newYaw = p.getYRot() + dYaw;
                float newPitch = clamp(p.getXRot() + dPitch, -89.0f, 89.0f);

                p.setYRot(newYaw);
                p.setXRot(newPitch);
                p.yRotO = newYaw;
                p.xRotO = newPitch;
            }

            // 2) MOVE (analog -> key states)
            if (payload.has("move")) {
                JsonObject move = payload.getAsJsonObject("move");
                double forward = move.has("forward") ? move.get("forward").getAsDouble() : 0.0;
                double strafe  = move.has("strafe") ? move.get("strafe").getAsDouble() : 0.0;

                boolean w = forward > 0.2;
                boolean s = forward < -0.2;
                boolean d = strafe  > 0.2;
                boolean a = strafe  < -0.2;

                Options opt = mc.options;
                opt.keyUp.setDown(w);
                opt.keyDown.setDown(s);
                opt.keyRight.setDown(d);
                opt.keyLeft.setDown(a);
            } else {
                Options opt = mc.options;
                opt.keyUp.setDown(false);
                opt.keyDown.setDown(false);
                opt.keyRight.setDown(false);
                opt.keyLeft.setDown(false);
            }

            // 3) JUMP
            mc.options.keyJump.setDown(payload.has("jump") && payload.get("jump").getAsBoolean());

            // 4) SPRINT / SNEAK
            mc.options.keySprint.setDown(payload.has("sprint") && payload.get("sprint").getAsBoolean());
            mc.options.keyShift.setDown(payload.has("sneak") && payload.get("sneak").getAsBoolean());

            // 5) HOTBAR SELECT
            if (payload.has("select_slot")) {
                int slot = payload.get("select_slot").getAsInt();
                if (slot >= 0 && slot < 9) {
                    p.getInventory().selected = slot;
                }
            }

            // 6) ATTACK (click-like)
            if (payload.has("attack")) {
                boolean down = payload.get("attack").getAsBoolean();
                mc.options.keyAttack.setDown(down);

                if (down && !lastAttackDown) {
                    doAttack(mc);
                }
                lastAttackDown = down;
            } else {
                mc.options.keyAttack.setDown(false);
                lastAttackDown = false;
            }

            // 7) USE (right click)
            if (payload.has("use")) {
                boolean down = payload.get("use").getAsBoolean();
                mc.options.keyUse.setDown(down);

                if (down && !lastUseDown) {
                    if (mc.gameMode != null) {
                        mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
                    }
                }
                lastUseDown = down;
            } else {
                mc.options.keyUse.setDown(false);
                lastUseDown = false;
            }

        } catch (Exception e) {
            status = "fail";
            reason = "exception";
            System.err.println("[WS] Action exec error: " + e.getMessage());
        }

        emitActionResult(actionSeq, actionId, status, reason);
    }

    private static void doAttack(Minecraft mc) {
        LocalPlayer p = mc.player;
        MultiPlayerGameMode gm = mc.gameMode;
        if (p == null || gm == null) return;

        if (p.getAttackStrengthScale(0.0f) < 0.9f) return;

        HitResult hr = mc.hitResult;
        if (hr == null) {
            p.swing(InteractionHand.MAIN_HAND);
            return;
        }

        if (hr.getType() == HitResult.Type.ENTITY) {
            Entity target = ((EntityHitResult) hr).getEntity();
            gm.attack(p, target);
            p.swing(InteractionHand.MAIN_HAND);
            return;
        }

        if (hr.getType() == HitResult.Type.BLOCK) {
            BlockHitResult bhr = (BlockHitResult) hr;
            BlockPos pos = bhr.getBlockPos();
            Direction dir = bhr.getDirection();
            gm.startDestroyBlock(pos, dir);
            p.swing(InteractionHand.MAIN_HAND);
            return;
        }

        p.swing(InteractionHand.MAIN_HAND);
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ───────────────────────────────────────────────
    // Emitters
    // ───────────────────────────────────────────────

    private void emitActionResult(int seq, String actionId, String status, String reason) {
        JsonObject ar = new JsonObject();
        ar.addProperty("action_id", actionId);
        ar.addProperty("status", normalizeStatus(status));
        ar.addProperty("server_tick", 0);
        ar.addProperty("ts_server", System.currentTimeMillis() / 1000.0);

        if (reason != null)
            ar.addProperty("reason", reason);

        JsonObject payload = new JsonObject();
        payload.add("action_result", ar);

        JsonObject msg = new JsonObject();
        msg.addProperty("proto", "1");
        msg.addProperty("kind", "action_result");
        msg.addProperty("seq", seq);
        msg.addProperty("timestamp", System.currentTimeMillis() / 1000.0);
        msg.add("payload", payload);

        if (canClientSendToBridge()) {
            this.send(BotMod.GSON.toJson(msg));
        }

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

        if (canClientSendToBridge()) {
            send(evt.toString());
        }
    }

    private void sendBridgeReady() {
        emitBridgeHealth("info", "bridge_ready");
        System.out.println("[WS] Bridge ready.");
    }

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

        if (canClientSendToBridge()) {
            send(evt.toString());
        }
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

        if (canClientSendToBridge()) {
            send(evt.toString());
        }
    }
}
