package com.example.aiagent.client;

import com.example.aiagent.BotMod;
import com.example.aiagent.BridgeConstants;
import com.example.aiagent.common.AgentModeSharedLogic;
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
        ControlMode previous = controlMode;
        if (previous != mode) {
            System.out.println("[AI-BOT][DEBUG][ControlMode] change " + previous + " -> " + mode
                    + " | thread=" + Thread.currentThread().getName());
        }
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

    /** Called when connection closes (any thread). Use to schedule reconnect in ClientBridgeHooks. */
    private Runnable onDisconnect = null;
    public void setOnDisconnect(Runnable r) { this.onDisconnect = r; }

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

    private static volatile ForgeWebSocketClient currentInstance;

    @Override
    public void onOpen(ServerHandshake handshake) {
        currentInstance = this;
        System.out.println("[WS] Connected to AI bridge");
        bridgeReady.set(true);

        lastAckSeq = -1;
        inflight.clear();

        JsonObject hello = new JsonObject();
        hello.addProperty("proto", "1");
        hello.addProperty("kind", "hello");
        hello.addProperty("role", BridgeConstants.ROLE_CLIENT);
        String modeStr = controlMode == ControlMode.PLAYER ? BridgeConstants.MODE_PLAYER : BridgeConstants.MODE_SERVER_BOT;
        hello.addProperty("control_mode", modeStr);
        send(hello.toString());
        System.out.println("[AI-BOT][DEBUG][WS] client hello sent role=client control_mode=" + modeStr);

        emitBridgeHealth("info", "connected");
        sendBridgeReady();

        if (onReconnect != null) onReconnect.run();
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        currentInstance = null;
        System.out.println("[WS] Connection closed: " + reason + " (code=" + code + ", remote=" + remote + ")");
        bridgeReady.set(false);

        Minecraft mc = Minecraft.getInstance();
        if (mc != null) mc.execute(() -> releaseAllKeys(mc));

        if (onDisconnect != null) onDisconnect.run();
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

            // Mode-aware gating:
            // - PLAYER mode is allowed in both singleplayer and multiplayer.
            // - SERVER_BOT mode is only allowed in integrated singleplayer.
            if (!isWsControlAllowedForMode(mode)) {
                System.out.println("[WS] Dropping action: ws control not allowed for mode=" + mode + " seq=" + seq);
                if (needsAck) {
                    emitActionResult(seq, actionId, "fail", "ws_control_not_allowed_for_mode");
                }
                return;
            }

            if (mode == ControlMode.SERVER_BOT) {
                mc.execute(() -> {
                    try {
                        releaseAllKeys(mc);
                        int deadlineMs = json.has("deadline_ms") ? json.get("deadline_ms").getAsInt() : 50;
                        forwardActionToServer(seq, actionId, deadlineMs, payload);

                        // Locally acknowledge forwarding so the Python bridge doesn't hang
                        if (needsAck) {
                            emitActionResult(seq, actionId, "success", "forwarded_to_server");
                        }
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
                        ActionExecResult r = handleStructuredAction(payload, mc);

                        // ACK only when requested (await_result==true)
                        if (needsAck) {
                            emitActionResult(seq, actionId, r.status, r.reason);
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

    /**
     * We allow WS-driven control in PLAYER mode even in multiplayer, because vanilla input replication
     * will send movement/attacks to the server normally.
     *
     * We only forbid WS control in SERVER_BOT mode unless we're in singleplayer/integrated-server,
     * to avoid a multiplayer client attempting to directly drive a server-side bot.
     */
    private static boolean isWsControlAllowedForMode(ControlMode mode) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return false;

        if (mode == ControlMode.PLAYER) {
            return true;
        }

        return mc.hasSingleplayerServer();
    }

    private boolean canClientSendToBridge() {
        // IMPORTANT: We must be able to ACK actions back to the Python bridge in multiplayer
        // when operating in PLAYER mode.
        return bridgeReady.get() && this.isOpen();
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

    private static final class ActionExecResult {
        final String status;
        final String reason;
        ActionExecResult(String status, String reason) {
            this.status = status;
            this.reason = reason;
        }
    }

    private ActionExecResult handleStructuredAction(JsonObject payload, Minecraft mc) {

        if (!isAiEnabled()) {
            releaseAllKeys(mc);
            return new ActionExecResult("blocked", "ai_disabled");
        }

        LocalPlayer p = mc.player;
        if (p == null) {
            return new ActionExecResult("fail", "no_player");
        }

        String status = "success";
        String reason = "";

        try {
            AgentModeSharedLogic.DecodedAction action =
                    AgentModeSharedLogic.decodeActionPayload(payload, MAX_YAW_PER_TICK, MAX_PITCH_PER_TICK, 3);

            // 1) LOOK
            if (action.hasLook()) {
                float newYaw = p.getYRot() + action.yawDelta();
                float newPitch = clamp(p.getXRot() + action.pitchDelta(), -89.0f, 89.0f);

                p.setYRot(newYaw);
                p.setXRot(newPitch);
                p.yRotO = newYaw;
                p.xRotO = newPitch;
            }

            // 2) MOVE + JUMP / SPRINT / SNEAK
            boolean w = action.forward() > 0.2f;
            boolean s = action.forward() < -0.2f;
            boolean d = action.strafe() > 0.2f;
            boolean a = action.strafe() < -0.2f;

            Options opt = mc.options;
            opt.keyUp.setDown(w);
            opt.keyDown.setDown(s);
            opt.keyRight.setDown(d);
            opt.keyLeft.setDown(a);

            opt.keyJump.setDown(action.jump());
            opt.keySprint.setDown(action.sprint());
            opt.keyShift.setDown(action.sneak());

            // 5) HOTBAR SELECT
            if (action.selectSlot() >= 0) {
                int slot = action.selectSlot();
                if (slot >= 0 && slot < 9) {
                    p.getInventory().selected = slot;
                }
            }

            // 6) ATTACK (edge-trigger only: do not hold key or vanilla will continuously dig when looking at blocks)
            if (payload.has("attack")) {
                boolean down = action.attack();
                if (down && !lastAttackDown) {
                    doAttack(mc);
                }
                lastAttackDown = down;
                // Do not set keyAttack.setDown(down) — that causes automatic block breaking when looking at blocks
            }

            // 7) USE (right click)
            if (payload.has("use")) {
                boolean down = action.use();
                mc.options.keyUse.setDown(down);

                if (down && !lastUseDown) {
                    if (mc.gameMode != null) {
                        mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
                    }
                }
                lastUseDown = down;
            }

        } catch (Exception e) {
            status = "fail";
            reason = "exception";
            System.err.println("[WS] Action exec error: " + e.getMessage());
        }

        return new ActionExecResult(status, reason);
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

    /**
     * Send config_update to the bridge for hot-reload (e.g. from GUI).
     * No-op if not connected. Payload is the runtime overlay (control_mode, policy.reward, policy.dqn).
     */
    public static void sendConfigUpdate(JsonObject payload) {
        ForgeWebSocketClient c = currentInstance;
        if (c == null || !c.isOpen() || payload == null) {
            System.out.println("[AI-BOT][DEBUG][WS] sendConfigUpdate SKIP: clientOpen=" + (c != null && c.isOpen())
                    + " payloadNull=" + (payload == null));
            return;
        }
        JsonObject msg = new JsonObject();
        msg.addProperty("proto", "1");
        msg.addProperty("kind", "config_update");
        msg.add("payload", payload);
        String cm = payload.has("control_mode") ? payload.get("control_mode").getAsString() : "?";
        System.out.println("[AI-BOT][DEBUG][WS] sendConfigUpdate ok control_mode=" + cm);
        c.send(BotMod.GSON.toJson(msg));
    }
}