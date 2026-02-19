package com.example.aiagent.server;

import com.example.aiagent.BotMod;
import com.example.aiagent.net.BotNet;
import com.example.aiagent.net.S2CBotStatePacket;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.network.PacketDistributor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Map;
import java.util.Set;

/**
 * FakeBotManager (SERVER SIDE)
 *
 * Ghost Architecture version:
 * - Server spawns & controls FakePlayer (authoritative)
 * - Server periodically sends AgentState packets for CLIENT-ONLY "ghost render"
 *
 * Key changes vs your file:
 * - No forced bot.player.tick() (avoid double-ticking)
 * - Strong "ensure added to world" logic
 * - Adds spawn/state/despawn packet hooks
 */
public class FakeBotManager {

    // Debug switches
    private static final boolean DEBUG_WS = false;
    private static final boolean DEBUG_NET = false;
    private static final boolean DEBUG_MOVE = false;
    private static final boolean DEBUG_DEEP_MOVE = false;

    private long dbgLastServerGameTime = Long.MIN_VALUE;
    private int dbgCallsThisServerTick = 0;

    // --- Step state machine (FIFO queue) ---
    private static final class StepRequest {
        final JsonObject action; // action payload (look/move/jump/etc)
        final int ticks; // duration
        final int seq; // from Action v1
        final String actionId; // from Action v1

        StepRequest(JsonObject action, int ticks, int seq, String actionId) {
            this.action = action;
            this.ticks = ticks;
            this.seq = seq;
            this.actionId = actionId;
        }
    }

    private void clearOneShotControls(FakeBot bot) {
        // things that should only apply for ONE tick/step
        bot.attack = false;
        bot.use = false;
        bot.selectSlot = -1;
        // if you have "swapHands", "pickBlock", etc. put them here
    }

    private void clearAllControls(FakeBot bot) {
        // one-shot
        clearOneShotControls(bot);

        // continuous
        bot.forward = 0f;
        bot.strafe = 0f;
        bot.jump = false;
        bot.sprint = false;
        bot.sneak = false;

        bot.ctrlForward = 0f;
        bot.ctrlStrafe = 0f;
        bot.ctrlJump = false;
        bot.ctrlSprint = false;
        bot.ctrlSneak = false;

        bot.ctrlYawDelta = 0f;
        bot.ctrlPitchDelta = 0f;
        bot.ctrlHoldTicks = 0;
    }

    public ServerPlayer getDefaultPlayerOrNull() {
        FakeBot b = getDefaultBot();
        return (b != null) ? b.player : null;
    }

    public static class FakeBot {
        public final ServerPlayer player;
        public final String botId = "agent0"; // for now we only support one bot, so hardcode the ID (important for ghost tracking)

        // Control state (latest received)
        public float yaw = 0f;
        public float pitch = 0f;
        public double forward = 0.0;
        public double strafe = 0.0;
        public boolean jump = false;
        public boolean sprint = false;
        public boolean sneak = false;
        public boolean attack = false;
        public boolean use = false;
        public int selectSlot = -1;
        public double stepStartX = 0, stepStartY = 0, stepStartZ = 0;

        // --- Continuous controls (persist for a short time) ---
        public float ctrlForward = 0f;
        public float ctrlStrafe  = 0f;
        public boolean ctrlJump  = false;
        public boolean ctrlSprint = false;
        public boolean ctrlSneak = false;

        float ctrlYawDelta = 0f;
        float ctrlPitchDelta = 0f;

        int ctrlHoldTicks = 0;  // counts down each server tick

        public boolean forceStateSync = false; // if true, will send AgentState to clients on next tick (use this if you do a teleport or other non-physics-based movement)

        // --- Step execution state ---
        public boolean stepActive = false;
        public int stepTicksRemaining = 0;
        public JsonObject stepAction = null;

        // Correlation (from Action v1)
        public int stepSeq = -1;
        public String stepActionId = null;

        // edge detection for click-like actions
        public boolean lastAttack = false;
        public boolean lastUse = false;

        // one-tick pulse for client ghost animation
        public boolean swingMainHandPulse = false;

        public FakeBot(ServerPlayer player) {
            this.player = player;
        }
    }

    // Fixed identity (important for ghost tracking)
    private static final UUID DEFAULT_BOT_UUID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final String DEFAULT_BOT_NAME = "Agent_Dig";

    // How often to send AgentState (in ticks). 2 = 10 updates/sec, 5 = 4
    // updates/sec.
    private static final int STATE_SYNC_PERIOD_TICKS = 1;

    private final Map<String, FakeBot> bots = new HashMap<>();
    private final ConcurrentLinkedQueue<String> pendingActionJson = new ConcurrentLinkedQueue<>();

    private final ConcurrentLinkedQueue<StepRequest> pendingSteps = new ConcurrentLinkedQueue<>();
    private long nextStepId = 1;

    private final java.util.concurrent.ConcurrentLinkedQueue<com.google.gson.JsonObject> completedStepResults = new java.util.concurrent.ConcurrentLinkedQueue<>();

    private int tickCounter = 0;

    public java.util.concurrent.ConcurrentLinkedQueue<com.google.gson.JsonObject> getCompletedStepResultsQueue() {
        return completedStepResults;
    }

    public Collection<FakeBot> getAllBots() {
        return bots.values();
    }

    public Set<String> getBotIds() {
        return bots.keySet();
    }

    public boolean teleportBotToPlayer(ServerPlayer caller, String botId) {
        FakeBot bot = bots.get(botId);
        if (bot == null || bot.player == null) return false;

        // For now: only support same-dimension teleports (keeps it safe & deterministic)
        if (bot.player.level() != caller.level()) {
            System.out.println("[AI-BOT] Refusing teleport: bot and caller are in different dimensions.");
            return false;
        }

        final double x = caller.getX();
        final double y = caller.getY();
        final double z = caller.getZ();
        final float yaw = caller.getYRot();
        final float pitch = caller.getXRot();

        // Stop motion/state that could fight the move
        bot.player.stopRiding();
        bot.player.setDeltaMovement(0, 0, 0);
        bot.player.fallDistance = 0;

        // IMPORTANT: raw position set bypasses ServerPlayer connection teleport logic
        bot.player.setPosRaw(x, y, z);

        // Rotation (set both current and "old" so it doesn't snap back)
        bot.player.setYRot(yaw);
        bot.player.setXRot(pitch);
        bot.player.yRotO = yaw;
        bot.player.xRotO = pitch;

        // Head/body for rendering correctness
        bot.player.setYHeadRot(yaw);
        bot.player.yBodyRot = yaw;

        // Mark dirty so tracking/clients update
        bot.player.hasImpulse = true;
        bot.player.hurtMarked = true;

        System.out.println("[AI-BOT] Teleported " + botId + " to "
                + bot.player.getX() + ", " + bot.player.getY() + ", " + bot.player.getZ());

        bot.forceStateSync = true;
        bot.swingMainHandPulse = false; // optional, avoid stray animation on teleport

        return true;
    }

    public JsonObject pollCompletedResult() {
        return completedStepResults.poll();
    }

    public void enqueueActionJson(String json) {
        if (json == null || json.isEmpty())
            return;
        pendingActionJson.offer(json);
    }

    /** Old signature kept for compatibility. Finds overworld and spawns there. */
    public void ensureDefaultBot(MinecraftServer server) {
        if (server == null)
            return;
        ServerLevel overworld = server.overworld();
        if (overworld == null) {
            System.out.println("[AI-BOT] ensureDefaultBot(server): overworld null, cannot spawn yet.");
            return;
        }
        ensureDefaultBot(server, overworld);
    }

    /** Spawn in provided level (recommended). */
    public void ensureDefaultBot(MinecraftServer server, ServerLevel level) {
        if (server == null || level == null) return;

        FakeBot existing = getDefaultBot();
        if (existing != null && existing.player != null && existing.player.isAlive()) {
            ensureAddedToWorld(level, existing.player);
            return;
        }

        GameProfile profile = new GameProfile(DEFAULT_BOT_UUID, DEFAULT_BOT_NAME);
        ServerPlayer fp = FakePlayerFactory.get(level, profile);

        // Physics
        fp.setNoGravity(false);
        fp.noPhysics = false;

        // Make it damageable / player-like
        fp.setInvulnerable(false);

        fp.getAbilities().invulnerable = false;
        fp.getAbilities().mayfly = false;
        fp.getAbilities().flying = false;
        fp.getAbilities().setWalkingSpeed(0.1f);
        fp.onUpdateAbilities();

        // IMPORTANT: use gameMode controller
        fp.gameMode.changeGameModeForPlayer(net.minecraft.world.level.GameType.SURVIVAL);

        // Clear hit immunity window
        fp.invulnerableTime = 0;
        fp.hurtTime = 0;
        fp.hurtMarked = true;

        // Place at spawn
        BlockPos spawn = level.getSharedSpawnPos();
        fp.moveTo(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, 0f, 0f);

        ensureAddedToWorld(level, fp);

        FakeBot bot = new FakeBot(fp);
        bots.put(bot.botId, bot);

        System.out.println("[AI-BOT] Default FakeBot spawned: " + DEFAULT_BOT_NAME
                + " entityId=" + fp.getId()
                + " uuid=" + fp.getUUID()
                + " at " + spawn
                + " in " + level.dimension().location());
    }

    /**
     * Called every server tick:
     * - drains queued actions (latest wins)
     * - applies control state to FakePlayer(s)
     * - periodically syncs state to clients for ghost rendering
     */
    public void tick() {
        tickCounter++;

        // DEBUG: measure how often this method runs per actual server tick
        // Use any bot's level to sample authoritative gameTime
        ServerLevel anyLevel = null;
        for (FakeBot b : bots.values()) {
            if (b != null && b.player != null && (b.player.level() instanceof ServerLevel sl)) {
                anyLevel = sl;
                break;
            }
        }
        if (anyLevel != null) {
            long gt = anyLevel.getGameTime();
            if (gt == dbgLastServerGameTime) {
                dbgCallsThisServerTick++;
                // Only print when it becomes > 1 to avoid noise
                if (dbgCallsThisServerTick == 2 || dbgCallsThisServerTick == 3 || dbgCallsThisServerTick == 5) {
                    if (DEBUG_NET) {
                        System.out.println("[AI-BOT][DBG][SRV] FakeBotManager.tick() called multiple times in same serverTick="
                            + gt + " callsSoFar=" + dbgCallsThisServerTick + " tickCounter=" + tickCounter
                            + " thread=" + Thread.currentThread().getName());
                    }
                }
            } else {
                dbgLastServerGameTime = gt;
                dbgCallsThisServerTick = 1;
            }
        }

        // Convert incoming JSON into queued steps
        drainActions();

        for (FakeBot bot : bots.values()) {
            if (bot == null || bot.player == null)
                continue;
            if (!bot.player.isAlive())
                continue;
            if (!(bot.player.level() instanceof ServerLevel level))
                continue;

            bot.player.setNoGravity(false);
            bot.player.noPhysics = false;

            // --- State sync to clients for ghost rendering ---
            if (bot.forceStateSync || (tickCounter % STATE_SYNC_PERIOD_TICKS) == 0) {
                final boolean wasForced = bot.forceStateSync;
                bot.forceStateSync = false;

                final boolean swingPulse = bot.swingMainHandPulse;
                final long serverTick = level.getGameTime();

                // velocity encoded as blocks/sec (BPS)
                final Vec3 velBps = bot.player.getDeltaMovement().scale(20.0);

                // rotation: send head + body + pitch
                final float headYaw = bot.player.getYHeadRot();
                final float bodyYaw = bot.player.yBodyRot;   // IMPORTANT: torso yaw
                final float pitch   = bot.player.getXRot();

                S2CBotStatePacket msg = new S2CBotStatePacket(
                        bot.botId,
                        bot.player.getId(),
                        bot.player.getUUID(),
                        serverTick,
                        bot.player.getX(), bot.player.getY(), bot.player.getZ(),
                        velBps.x, velBps.y, velBps.z,
                        headYaw, bodyYaw, pitch,
                        bot.player.onGround(),
                        swingPulse
                );

                System.out.println("[AI-BOT][DBG][SRV-SEND] bot=" + bot.player.getGameProfile().getName()
                        + " tick=" + serverTick
                        + " force=" + wasForced
                        + " headYaw=" + headYaw
                        + " bodyYaw=" + bodyYaw
                        + " pitch=" + pitch
                        + " pos=(" + bot.player.getX() + "," + bot.player.getY() + "," + bot.player.getZ() + ")"
                        + " velBPS=(" + velBps.x + "," + velBps.y + "," + velBps.z + ")"
                        + " onGround=" + bot.player.onGround()
                );

                BotNet.CHANNEL.send(PacketDistributor.DIMENSION.with(() -> level.dimension()), msg);
                bot.swingMainHandPulse = false;
            }

            // -----------------------------
            // STEP STATE MACHINE
            // -----------------------------

            // Start a new step if idle
            if (!bot.stepActive) {
                StepRequest req = pendingSteps.poll();
                if (req != null) {
                    bot.stepActive = true;
                    bot.stepTicksRemaining = Math.max(1, req.ticks);
                    bot.stepAction = req.action;

                    bot.stepSeq = req.seq;
                    bot.stepActionId = req.actionId;

                    bot.stepStartX = bot.player.getX();
                    bot.stepStartY = bot.player.getY();
                    bot.stepStartZ = bot.player.getZ();

                    // Reset ONLY one-shot actions at step start; keep continuous ctrl* state
                    clearOneShotControls(bot);

                    if (DEBUG_MOVE) {
                        System.out.println("[STEP] START name=" + bot.player.getGameProfile().getName()
                                + " seq=" + req.seq
                                + " action_id=" + req.actionId
                                + " ticks=" + req.ticks
                                + " hasMove=" + (req.action != null && req.action.has("move")));
                    }
                }
            }

            // If there is no active step, do nothing this tick (vanilla physics still runs)
            if (!bot.stepActive) {
                continue;
            }

            // -----------------------------
            // ACTIVE STEP TICK
            // -----------------------------

            // Invariant: stepActive must always have valid correlation fields
            if (bot.stepSeq < 0 || bot.stepActionId == null || bot.stepActionId.isBlank()) {
                System.out.println("[AI-BOT] WARNING: stepActive but missing seq/action_id; forcing reset. "
                        + "name=" + bot.player.getGameProfile().getName()
                        + " stepSeq=" + bot.stepSeq
                        + " actionId=" + bot.stepActionId);

                // Full reset because state is corrupted
                clearAllControls(bot);
                bot.stepActive = false;
                bot.stepTicksRemaining = 0;
                bot.stepAction = null;
                bot.stepSeq = -1;
                bot.stepActionId = null;
                continue;
            }

            // Apply the step action each tick
            applyPayloadToBot(bot.stepAction, bot);

            if (DEBUG_MOVE) {
                System.out.println("[STEP TICK] seq=" + bot.stepSeq
                        + " action_id=" + bot.stepActionId
                        + " f=" + bot.forward
                        + " s=" + bot.strafe
                        + " jump=" + bot.jump
                        + " sprint=" + bot.sprint
                        + " sneak=" + bot.sneak);
            }

            if (bot.ctrlHoldTicks > 0) {
                // Apply look deltas deterministically
                bot.yaw += bot.ctrlYawDelta;
                bot.pitch += bot.ctrlPitchDelta;
                bot.pitch = Mth.clamp(bot.pitch, -89f, 89f);

                // Materialize movement controls for this tick
                bot.forward = bot.ctrlForward;
                bot.strafe  = bot.ctrlStrafe;
                bot.jump    = bot.ctrlJump;
                bot.sprint  = bot.ctrlSprint;
                bot.sneak   = bot.ctrlSneak;

                bot.ctrlHoldTicks--;
            } else {
                // No recent control update -> decay only the ctrl deltas.
                // DO NOT wipe bot.forward/strafe/jump here if step payloads are expected to drive them.
                bot.ctrlYawDelta = 0f;
                bot.ctrlPitchDelta = 0f;

                // Optional: if you want full stop when no control packets, uncomment:
                // bot.forward = 0.0;
                // bot.strafe  = 0.0;
                // bot.jump    = false;
                // bot.sprint  = false;
                // bot.sneak   = false;
            }

            // 1) apply look/hotbar/attack/use
            applyLookAndHotbarAndActions(bot, level);

            // 2) apply movement FOR THIS STEP ONLY
            applyMovementTravel(bot);

            // Count down
            // Count down (exactly once per tick)
            bot.stepTicksRemaining--;

            if (bot.stepTicksRemaining <= 0) {
                final int finishedSeq = bot.stepSeq;
                final String finishedActionId = bot.stepActionId;

                // Always emit action_result for a valid seq/action_id (even if obs has issues)
                if (finishedSeq < 0 || finishedActionId == null || finishedActionId.isBlank()) {
                    System.out.println("[AI-BOT] ERROR: invalid finishedSeq/actionId at finish; skipping emit. "
                            + "name=" + bot.player.getGameProfile().getName()
                            + " finishedSeq=" + finishedSeq
                            + " actionId=" + finishedActionId);
                } else {
                    // 1) OBSERVATION (best-effort: do not let an exception block action_result)
                    try {
                        JsonObject obsMsg = buildObservationEvent(finishedSeq, bot, level);
                        completedStepResults.offer(obsMsg);
                    } catch (Throwable t) {
                        System.out.println("[AI-BOT] ERROR: buildObservationEvent failed seq=" + finishedSeq
                                + " action_id=" + finishedActionId
                                + " err=" + t);
                    }

                    // 2) ACTION_RESULT (ACK) - MUST happen for await_result=true actions
                    try {
                        JsonObject arMsg = buildActionResultEvent(finishedSeq, finishedActionId, "success", null);
                        completedStepResults.offer(arMsg);
                    } catch (Throwable t) {
                        System.out.println("[AI-BOT] ERROR: buildActionResultEvent failed seq=" + finishedSeq
                                + " action_id=" + finishedActionId
                                + " err=" + t);
                    }

                    if (DEBUG_MOVE) System.out.println("[AI-BOT] COMPLETE seq=" + finishedSeq + " action_id=" + finishedActionId);
                    if (DEBUG_WS) System.out.println("[AI-BOT] ENQUEUE obs+action_result seq=" + finishedSeq + " action_id=" + finishedActionId);
                }

                // Step is finished -> reset step state (IMPORTANT)
                clearOneShotControls(bot);
                bot.stepActive = false;
                bot.stepTicksRemaining = 0;
                bot.stepAction = null;
                bot.stepSeq = -1;
                bot.stepActionId = null;

            } else {
                // Step still active -> ONLY clear one-shot clicks
                clearOneShotControls(bot);
            }

        }

    }

    private void drainActions() {
        String s;
        while ((s = pendingActionJson.poll()) != null) {
            try {
                JsonObject msg = BotMod.GSON.fromJson(s, JsonObject.class);

                // --- NEW: Accept Action v1 envelope format ---
                // { "proto":"1", "kind":"action", "seq":INT, "action_id":"...", "deadline_ms":INT, "payload":{...} }
                if (msg.has("proto") && msg.has("kind")
                        && "1".equals(msg.get("proto").getAsString())
                        && "action".equals(msg.get("kind").getAsString())
                        && msg.has("seq") && msg.has("action_id") && msg.has("payload")) {

                    int seq = msg.get("seq").getAsInt();
                    String actionId = msg.get("action_id").getAsString();
                    int deadlineMs = msg.has("deadline_ms") ? msg.get("deadline_ms").getAsInt() : 50;

                    if (seq < 0) continue;
                    if (actionId == null || actionId.isBlank()) continue;
                    if (deadlineMs <= 0) deadlineMs = 50;

                    if (!msg.get("payload").isJsonObject()) continue;
                    JsonObject action = msg.getAsJsonObject("payload");

                    int ticks = Math.max(1, (deadlineMs + 49) / 50); // 50ms per tick

                    if (pendingSteps.size() > 200) pendingSteps.poll(); // keep latency bounded
                    pendingSteps.offer(new StepRequest(action, ticks, seq, actionId));

                    if (DEBUG_MOVE) {
                        System.out.println("[DRAIN] queued ENVELOPE step seq=" + seq
                                + " action_id=" + actionId + " ticks=" + ticks
                                + " keys=" + action.keySet());
                    }
                    continue;
                }
                else if (msg == null)
                    continue;

                // Internal step format (created by ServerBridgeWebSocketClient):
                // { "cmd":"step", "ticks":N, "seq":INT, "action_id":"...", "action":{...} }
                if (msg.has("cmd") && "step".equals(msg.get("cmd").getAsString())
                        && msg.has("ticks") && msg.has("action")
                        && msg.has("seq") && msg.has("action_id")) {

                    int ticks = msg.get("ticks").getAsInt();
                    if (ticks <= 0)
                        ticks = 1;

                    int seq = msg.get("seq").getAsInt();
                    String actionId = msg.get("action_id").getAsString();
                    if (actionId == null || actionId.isBlank())
                        continue;

                    if (!msg.get("action").isJsonObject()) {
                        if (DEBUG_MOVE)
                            System.out.println("[DRAIN] action is not object: " + msg.get("action"));
                        continue;
                    }

                    JsonObject action = msg.getAsJsonObject("action");
                    if (action == null)
                        continue;

                    if (pendingSteps.size() > 200) {
                        // drop oldest to keep latency low
                        pendingSteps.poll();
                    }

                    pendingSteps.offer(new StepRequest(action, ticks, seq, actionId));

                    if (DEBUG_MOVE) {
                        System.out.println("[DRAIN] queued step seq=" + seq
                                + " action_id=" + actionId
                                + " ticks=" + ticks
                                + " hasMove=" + action.has("move")
                                + " keys=" + action.keySet());
                        if (action.has("move")) {
                            JsonObject mv = action.getAsJsonObject("move");
                            System.out.println("[DRAIN] move payload forward="
                                    + (mv.has("forward") ? mv.get("forward").getAsDouble() : null)
                                    + " strafe="
                                    + (mv.has("strafe") ? mv.get("strafe").getAsDouble() : null));
                        }
                    }
                    continue;
                }

                // Back-compat: drop unknown formats (prevents seq=-1 steps that never ACK)
                if (DEBUG_MOVE) {
                    System.out.println("[DRAIN] dropped unknown action json: " + msg);
                }

            } catch (Exception ignored) {
            }
        }
    }

    private FakeBot getDefaultBot() {
        for (FakeBot b : bots.values()) {
            if (b.player != null && DEFAULT_BOT_UUID.equals(b.player.getUUID())) {
                return b;
            }
        }
        return null;
    }

    // --- WS Event Builders (Bridge v1) ---
    private JsonObject buildActionResultEvent(int seq, String actionId, String status, String reason) {
        JsonObject root = new JsonObject();
        root.addProperty("proto", "1");
        root.addProperty("kind", "action_result");
        root.addProperty("seq", seq);

        JsonObject ar = new JsonObject();
        ar.addProperty("action_id", actionId);
        ar.addProperty("status", status); // success|fail|cooldown|blocked|timeout
        if (reason != null && !reason.isBlank()) ar.addProperty("reason", reason);

        ar.addProperty("server_tick", tickCounter);
        ar.addProperty("ts_server", System.currentTimeMillis() / 1000.0);

        JsonObject payload = new JsonObject();
        payload.add("action_result", ar);

        root.add("payload", payload);
        return root;
    }

    /**
     * Optional: only use if your Python expects observation as kind="observation".
     * If your Python validates events strictly against event.schema.json, DO NOT send this
     * until you add an observation schema on the Python side.
     */


// --- Observation Builder (Bridge v1) ---
// Builds an observation payload that ALWAYS includes the required keys:
// pose, rays, front_clear, world, inventory, collision (+ entities as empty list)

private JsonObject buildObservationEvent(int seq, FakeBot bot, ServerLevel level) {
    JsonObject root = new JsonObject();
    root.addProperty("timestamp", System.currentTimeMillis() / 1000.0);
    root.addProperty("proto", "1");
    root.addProperty("kind", "observation");
    root.addProperty("seq", seq);

    JsonObject payload = new JsonObject();

    // --------------------
    // pose (required)
    // --------------------
    JsonObject pose = new JsonObject();
    pose.addProperty("x", bot.player.getX());
    pose.addProperty("y", bot.player.getY());
    pose.addProperty("z", bot.player.getZ());
    pose.addProperty("yaw", bot.player.getYRot());
    pose.addProperty("pitch", bot.player.getXRot());
    payload.add("pose", pose);

    // --------------------
    // rays (required)
    // --------------------
    // Use your existing rays if you already compute them; otherwise default to empty array.
    // If your Python schema requires items, empty array is still valid if "rays" itself is required.
    // --------------------
    // rays (required) — default placeholder
    // --------------------
    com.google.gson.JsonArray rays = new com.google.gson.JsonArray();

    // 16 rays @ 22.5° increments, default "no hit" at max distance
    for (int i = 0; i < 16; i++) {
        JsonObject r = new JsonObject();
        r.addProperty("hit", false);
        r.addProperty("dist", 5.0);
        r.addProperty("angle_deg", i * 22.5);
        rays.add(r);
    }

    payload.add("rays", rays);


    // --------------------
    // front_clear (required)
    // --------------------
    // Default: assume clear unless your rays say otherwise.
    boolean frontClear = true;
    try {
        JsonObject r0 = rays.get(0).getAsJsonObject();
        boolean hit = r0.get("hit").getAsBoolean();
        double dist = r0.get("dist").getAsDouble();
        frontClear = !(hit && dist < 1.25);
    } catch (Exception ignored) {}

    payload.addProperty("front_clear", frontClear);


    // --------------------
    // world (required)
    // --------------------
    JsonObject world = new JsonObject();
    long dayTime = level.getDayTime() % 24000L;
    world.addProperty("time_of_day", (double) dayTime);

    String weather = "clear";
    if (level.isThundering()) weather = "thunder";
    else if (level.isRaining()) weather = "rain";
    world.addProperty("weather", weather);

    String biomeName = "unknown";
    try {
        var biomeKey = level.getBiome(bot.player.blockPosition()).unwrapKey();
        if (biomeKey.isPresent()) biomeName = biomeKey.get().location().toString();
    } catch (Exception ignored) {}
    world.addProperty("biome", biomeName);

    payload.add("world", world);

    // --------------------
    // inventory (required)
    // --------------------
    JsonObject inv = new JsonObject();
    inv.addProperty("selected_slot", bot.player.getInventory().selected);

    com.google.gson.JsonArray hotbar = new com.google.gson.JsonArray();
    for (int i = 0; i < 9; i++) {
        var stack = bot.player.getInventory().getItem(i);
        JsonObject it = new JsonObject();
        String id = "air";
        try {
            id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        } catch (Exception ignored) {}
        it.addProperty("id", id);
        it.addProperty("count", stack.getCount());
        hotbar.add(it);
    }
    inv.add("hotbar", hotbar);
    payload.add("inventory", inv);

    // --------------------
    // collision (required)
    // --------------------
    JsonObject collision = new JsonObject();
    collision.addProperty("is_grounded", bot.player.onGround());
    collision.addProperty("is_colliding", bot.player.horizontalCollision || bot.player.verticalCollision);

    // "no_progress" default heuristic: if movement over step is tiny
    double dx = bot.player.getX() - bot.stepStartX;
    double dz = bot.player.getZ() - bot.stepStartZ;
    boolean noProgress = (dx * dx + dz * dz) < 0.0004; // ~2cm threshold squared
    collision.addProperty("no_progress", noProgress);

    payload.add("collision", collision);

    // --------------------
    // entities (optional in your schema, but nice to include)
    // --------------------
    payload.add("entities", new com.google.gson.JsonArray());

    root.add("payload", payload);
    return root;
}

    private void applyPayloadToBot(JsonObject payload, FakeBot bot) {
        if (payload == null || bot == null) return;

        // -----------------------------
        // LOOK (deltas)  accept multiple key spellings
        // -----------------------------
        if (payload.has("look") && payload.get("look").isJsonObject()) {
            JsonObject look = payload.getAsJsonObject("look");

            float dyaw = 0f;
            float dpitch = 0f;

            if (look.has("dYaw")) dyaw = look.get("dYaw").getAsFloat();
            else if (look.has("yaw_delta")) dyaw = look.get("yaw_delta").getAsFloat();
            else if (look.has("dyaw")) dyaw = look.get("dyaw").getAsFloat();

            if (look.has("dPitch")) dpitch = look.get("dPitch").getAsFloat();
            else if (look.has("pitch_delta")) dpitch = look.get("pitch_delta").getAsFloat();
            else if (look.has("dpitch")) dpitch = look.get("dpitch").getAsFloat();

            bot.ctrlYawDelta = dyaw;
            bot.ctrlPitchDelta = dpitch;

            bot.ctrlHoldTicks = Math.max(bot.ctrlHoldTicks, 3);
        }

        // -----------------------------
        // MOVE (forward/strafe + optionally jump/sprint/sneak inside move)
        // -----------------------------
        if (payload.has("move") && payload.get("move").isJsonObject()) {
            JsonObject move = payload.getAsJsonObject("move");

            bot.ctrlForward = move.has("forward") ? (float) move.get("forward").getAsDouble() : 0f;
            bot.ctrlStrafe  = move.has("strafe")  ? (float) move.get("strafe").getAsDouble()  : 0f;

            // Some senders embed these inside move
            if (move.has("jump"))   bot.ctrlJump   = move.get("jump").getAsBoolean();
            if (move.has("sprint")) bot.ctrlSprint = move.get("sprint").getAsBoolean();
            if (move.has("sneak"))  bot.ctrlSneak  = move.get("sneak").getAsBoolean();

            bot.ctrlHoldTicks = Math.max(bot.ctrlHoldTicks, 3);

            if (DEBUG_MOVE) {
                System.out.println("[PAYLOAD MOVE] keys=" + payload.keySet()
                        + " f=" + bot.ctrlForward + " s=" + bot.ctrlStrafe
                        + " jump=" + bot.ctrlJump + " sprint=" + bot.ctrlSprint + " sneak=" + bot.ctrlSneak);
            }
        } else {
            if (DEBUG_MOVE) System.out.println("[PAYLOAD NO-MOVE] keys=" + payload.keySet());
        }

        // -----------------------------
        // FLAGS also allowed top-level
        // -----------------------------
        if (payload.has("jump")) {
            bot.ctrlJump = payload.get("jump").getAsBoolean();
            bot.ctrlHoldTicks = Math.max(bot.ctrlHoldTicks, 3);
        }
        if (payload.has("sprint")) {
            bot.ctrlSprint = payload.get("sprint").getAsBoolean();
            bot.ctrlHoldTicks = Math.max(bot.ctrlHoldTicks, 3);
        }
        if (payload.has("sneak")) {
            bot.ctrlSneak = payload.get("sneak").getAsBoolean();
            bot.ctrlHoldTicks = Math.max(bot.ctrlHoldTicks, 3);
        }

        // -----------------------------
        // HOTBAR + DISCRETE ACTIONS
        // -----------------------------
        if (payload.has("select_slot")) {
            bot.selectSlot = payload.get("select_slot").getAsInt();
        }

        bot.attack = payload.has("attack") && payload.get("attack").getAsBoolean();
        bot.use    = payload.has("use")    && payload.get("use").getAsBoolean();
    }



    private void applyMovementTravel(FakeBot bot) {
        ServerPlayer p = bot.player;
        if (p == null) return;

        // Convert bot inputs to [-1..1]
        float forward = (float) bot.forward;
        float strafe  = (float) bot.strafe;

        // deadzone + clamp
        if (Math.abs(forward) < 0.2f) forward = 0f;
        if (Math.abs(strafe)  < 0.2f) strafe  = 0f;

        forward = Mth.clamp(forward, -1f, 1f);
        strafe  = Mth.clamp(strafe,  -1f, 1f);

        // Apply flags
        p.setSprinting(bot.sprint);
        p.setShiftKeyDown(bot.sneak);
        p.setJumping(bot.jump);

        // Optional: only set speed if you really want to override vanilla attribute behavior
        // If you suspect this is interfering, comment this out and rely on Attributes.MOVEMENT_SPEED.
        p.setSpeed(bot.sprint ? 0.13f : 0.10f);

        Vec3 pos0 = p.position();
        Vec3 v0 = p.getDeltaMovement();

        // NOTE: travel expects (strafe, vertical, forward)
        p.travel(new Vec3(strafe, 0.0, forward));

        Vec3 pos1 = p.position();
        Vec3 v1 = p.getDeltaMovement();

        if (DEBUG_MOVE) {
            System.out.println("[TRAVEL] seq=" + bot.stepSeq
                    + " f=" + forward + " s=" + strafe
                    + " posΔ=(" + (pos1.x - pos0.x) + ", " + (pos1.y - pos0.y) + ", " + (pos1.z - pos0.z) + ")"
                    + " vel0=" + v0 + " vel1=" + v1
                    + " onGround=" + p.onGround()
                    + " hColl=" + p.horizontalCollision
                    + " vColl=" + p.verticalCollision);
        }
    }




    private void applyLookAndHotbarAndActions(FakeBot bot, ServerLevel level) {
        ServerPlayer p = bot.player;

        // -----------------------------
        // 1) LOOK
        // -----------------------------
        bot.pitch = Mth.clamp(bot.pitch, -89f, 89f);

        // Save previous rotations for smooth rendering
        p.yRotO = p.getYRot();
        p.xRotO = p.getXRot();
        p.yBodyRotO = p.yBodyRot;
        p.yHeadRotO = p.getYHeadRot();

        // Apply current look (head)
        p.setYRot(bot.yaw);
        p.setXRot(bot.pitch);
        p.setYHeadRot(bot.yaw);

        // Make body follow head gradually (less robotic)
        float body = p.yBodyRot;
        float targetBody = bot.yaw;
        float maxBodyStep = 10.0f; // degrees per tick (tune 6–12)
        float delta = Mth.wrapDegrees(targetBody - body);
        delta = Mth.clamp(delta, -maxBodyStep, maxBodyStep);
        p.yBodyRot = body + delta;

        // Clamp head relative to body (vanilla-like)
        float headDelta = Mth.wrapDegrees(bot.yaw - p.yBodyRot);
        headDelta = Mth.clamp(headDelta, -75f, 75f);
        p.setYHeadRot(p.yBodyRot + headDelta);

        // -----------------------------
        // 2) HOTBAR
        // -----------------------------
        if (bot.selectSlot >= 0 && bot.selectSlot < 9) {
            p.getInventory().selected = bot.selectSlot;
            bot.selectSlot = -1;
        }

        // -----------------------------
        // 3) FLAGS
        // -----------------------------
        p.setSprinting(bot.sprint);
        p.setShiftKeyDown(bot.sneak);

        // -----------------------------
        // 4) ATTACK (edge-trigger)
        // -----------------------------
        if (bot.attack && !bot.lastAttack) {
            doServerAttack(level, p);
            bot.swingMainHandPulse = true;
        }
        bot.lastAttack = bot.attack;

        // -----------------------------
        // 5) USE (edge-trigger)
        // -----------------------------
        if (bot.use && !bot.lastUse) {
            p.swing(InteractionHand.MAIN_HAND, true);
            bot.swingMainHandPulse = true;
            // Later: real right-click use
            // p.gameMode.useItem(p, level, p.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND);
        }
        bot.lastUse = bot.use;
    }


    private void doServerAttack(ServerLevel level, ServerPlayer p) {
        Vec3 from = p.getEyePosition();
        Vec3 look = p.getLookAngle();
        double maxDist = 4.5;
        Vec3 to = from.add(look.scale(maxDist));

        ClipContext ctx = new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p);
        BlockHitResult bhr = level.clip(ctx);

        AABB box = p.getBoundingBox().expandTowards(look.scale(maxDist)).inflate(1.0);
        EntityHitResult ehr = ProjectileUtil.getEntityHitResult(level, p, from, to, box,
                (e) -> e != null && e.isAlive() && e != p);

        if (ehr != null && ehr.getEntity() != null) {
            Entity target = ehr.getEntity();
            p.attack(target);
            p.swing(InteractionHand.MAIN_HAND);
            return;
        }

        if (bhr != null && bhr.getType() == HitResult.Type.BLOCK) {
            p.swing(InteractionHand.MAIN_HAND);
            return;
        }

        p.swing(InteractionHand.MAIN_HAND);
    }

    /**
     * Ensures the fake player is added to the ServerLevel entity list.
     * If it's already there, do nothing.
     */
    private void ensureAddedToWorld(ServerLevel level, ServerPlayer fp) {
        if (level == null || fp == null)
            return;
        if (fp.isRemoved())
            return;

        if (level.getEntity(fp.getId()) == null) {
            level.addFreshEntity(fp);
        }
    }

    public void despawnAll() {
        for (FakeBot b : bots.values()) {
            if (b == null || b.player == null)
                continue;

            try {

            } catch (Exception ignored) {
            }

            try {
                b.player.discard();
            } catch (Exception ignored) {
            }
        }

        bots.clear();
        pendingActionJson.clear();
    }

    private JsonObject buildObservationPayload(ServerLevel level, ServerPlayer p, FakeBot bot) {
        JsonObject payload = new JsonObject();

        // ---- pose (required)
        JsonObject pose = new JsonObject();
        pose.addProperty("x", p.getX());
        pose.addProperty("y", p.getY());
        pose.addProperty("z", p.getZ());
        pose.addProperty("yaw", p.getYRot());
        pose.addProperty("pitch", p.getXRot());
        payload.add("pose", pose);

        // ---- rays (required) : minimal valid = empty array
        payload.add("rays", new com.google.gson.JsonArray());

        // ---- front_clear (required) : simple 1-block ray in front
        payload.addProperty("front_clear", isFrontClear(level, p));

        // ---- world (required)
        JsonObject world = new JsonObject();
        world.addProperty("time_of_day", (double) (level.getDayTime() % 24000L));

        String weather = level.isThundering() ? "thunder" : (level.isRaining() ? "rain" : "clear");
        world.addProperty("weather", weather);

        String biome = level.getBiome(p.blockPosition())
                .unwrapKey()
                .map(k -> k.location().toString())
                .orElse("unknown");
        world.addProperty("biome", biome);

        payload.add("world", world);

        // ---- inventory (required)
        JsonObject inv = new JsonObject();
        inv.addProperty("selected_slot", p.getInventory().selected);

        com.google.gson.JsonArray hotbar = new com.google.gson.JsonArray();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = p.getInventory().getItem(i);
            String id = stack.isEmpty()
                    ? "minecraft:air"
                    : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();

            JsonObject slot = new JsonObject();
            slot.addProperty("id", id);
            slot.addProperty("count", stack.isEmpty() ? 0 : stack.getCount());
            hotbar.add(slot);
        }
        inv.add("hotbar", hotbar);
        payload.add("inventory", inv);

        // ---- collision (required)
        JsonObject collision = new JsonObject();
        collision.addProperty("is_grounded", p.onGround());

        // If these fields are accessible in your mappings, use them. If not, fallback
        // false.
        boolean isColliding = false;
        try {
            isColliding = p.horizontalCollision || p.verticalCollision;
        } catch (Throwable ignored) {
        }

        collision.addProperty("is_colliding", isColliding);

        // no_progress: moved less than ~1cm during the step
        double dx = p.getX() - bot.stepStartX;
        double dy = p.getY() - bot.stepStartY;
        double dz = p.getZ() - bot.stepStartZ;
        boolean noProgress = (dx * dx + dy * dy + dz * dz) < 1.0e-4;
        collision.addProperty("no_progress", noProgress);

        payload.add("collision", collision);

        return payload;
    }

    private boolean isFrontClear(ServerLevel level, ServerPlayer p) {
        Vec3 from = p.getEyePosition();
        Vec3 to = from.add(p.getLookAngle().scale(1.0)); // 1 block ahead
        ClipContext ctx = new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p);
        BlockHitResult hit = level.clip(ctx);
        return hit.getType() == HitResult.Type.MISS;
    }

}