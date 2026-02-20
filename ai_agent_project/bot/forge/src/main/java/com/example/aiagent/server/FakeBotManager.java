package com.example.aiagent.server;

import com.example.aiagent.server.DamageableFakePlayer;
import com.example.aiagent.server.DamageableFakePlayerFactory;
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
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
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
    private static final boolean DEBUG_HIT = false;
    private static final boolean DEBUG_KNOCK = true;

    private long dbgLastServerGameTime = Long.MIN_VALUE;
    private int dbgCallsThisServerTick = 0;
    private long lastProcessedGameTime = Long.MIN_VALUE;

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

    private static void clearOneShotControls(FakeBot bot) {
        // things that should only apply for ONE tick/step
        bot.attack = false;
        bot.use = false;
        bot.selectSlot = -1;
        bot.equipArmorFromSlot = -1;
        bot.swapMainhandFromSlot = -1;
        bot.dropFromSlot = -1;
        bot.dropCount = 1;
    }

    private static void clearAllControls(FakeBot bot) {
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
        public ServerPlayer player; // <-- NOT final
        public final String botId = "agent0";

        public final UUID uuid;
        public final String name;

        // Track last death position for respawn placement (can be null)
        public Vec3 lastDeathPos = null;

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
        public float ctrlStrafe = 0f;
        public boolean ctrlJump = false;
        public boolean ctrlSprint = false;
        public boolean ctrlSneak = false;

        float ctrlYawDelta = 0f;
        float ctrlPitchDelta = 0f;

        int ctrlHoldTicks = 0; // counts down each server tick

        public boolean forceStateSync = false; // if true, will send AgentState to clients on next tick (use this if you
                                               // do a teleport or other non-physics-based movement)

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

        // Fall damage tracking (deterministic, since we don't run vanilla player tick)
        public boolean wasOnGround = true;
        public double lastY = 0.0;
        public float fallDistanceAcc = 0.0f;

        public ItemStack[] savedInv = new ItemStack[36];
        public ItemStack savedOffhand = ItemStack.EMPTY;
        public ItemStack[] savedArmor = new ItemStack[4]; // FEET, LEGS, CHEST, HEAD
        public int savedSelected = 0;

        // One-shot inventory actions (cleared every tick)
        public int equipArmorFromSlot = -1;      // 0..35
        public int swapMainhandFromSlot = -1;    // 0..35
        public int dropFromSlot = -1;            // 0..35
        public int dropCount = 1;

        public FakeBot(ServerPlayer player, UUID uuid, String name) {
            this.player = player;
            this.uuid = uuid;
            this.name = name;
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
        if (bot == null || bot.player == null)
            return false;

        // For now: only support same-dimension teleports (keeps it safe &
        // deterministic)
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
        if (server == null || level == null)
            return;

        FakeBot bot = getDefaultBot();

        // If alive, just ensure it's in-world
        if (bot != null && bot.player != null && bot.player.isAlive()) {
            ensureAddedToWorld(level, bot.player);
            return;
        }

        // Create a fresh fake player instance
        GameProfile profile = new GameProfile(DEFAULT_BOT_UUID, DEFAULT_BOT_NAME);
        ServerPlayer fp = DamageableFakePlayerFactory.get(level, profile);

        // Physics
        fp.setNoGravity(false);
        fp.noPhysics = false;

        // Damageable / player-like
        fp.setInvulnerable(false);
        fp.getAbilities().invulnerable = false;
        fp.getAbilities().mayfly = false;
        fp.getAbilities().flying = false;
        fp.getAbilities().setWalkingSpeed(0.1f);
        fp.onUpdateAbilities();

        fp.gameMode.changeGameModeForPlayer(net.minecraft.world.level.GameType.SURVIVAL);

        // Clear hit immunity window
        fp.invulnerableTime = 0;
        fp.hurtTime = 0;
        fp.hurtMarked = true;

        // Respawn position: last death pos if available, else world spawn
        BlockPos spawnPos = level.getSharedSpawnPos();
        Vec3 pos = (bot != null) ? bot.lastDeathPos : null;
        if (pos == null) {
            pos = new Vec3(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5);
        }

        fp.moveTo(pos.x, pos.y, pos.z, 0f, 0f);
        ensureAddedToWorld(level, fp);
        settleSpawnPhysicsOnce(level, fp);

        if (bot == null) {
            bot = new FakeBot(fp, DEFAULT_BOT_UUID, DEFAULT_BOT_NAME);
            bots.put(bot.botId, bot);
        } else {
            bot.player = fp;
        }

        // IMPORTANT: always publish immediately after spawn settle (for BOTH branches)
        bot.forceStateSync = true;          // guarantees sendBotState won't early return
        sendBotState(level, bot);

        System.out.println("[AI-BOT] Default FakeBot spawned: " + DEFAULT_BOT_NAME
                + " entityId=" + fp.getId()
                + " uuid=" + fp.getUUID()
                + " pos=(" + pos.x + "," + pos.y + "," + pos.z + ")"
                + " worldSpawn=" + spawnPos
                + " dim=" + level.dimension().location());
    }

    public void onBotDied(ServerPlayer dead, Vec3 deathPos) {
        FakeBot bot = getBotByPlayer(dead);
        if (bot == null)
            return;

        bot.lastDeathPos = deathPos;

        dead.remove(net.minecraft.world.entity.Entity.RemovalReason.KILLED);
        bot.player = null;

        clearAllControls(bot);
        bot.stepActive = false;
        bot.stepTicksRemaining = 0;
        bot.stepAction = null;
        bot.stepSeq = -1;
        bot.stepActionId = null;

        bot.forceStateSync = true;
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
                        System.out.println(
                                "[AI-BOT][DBG][SRV] FakeBotManager.tick() called multiple times in same serverTick="
                                        + gt + " callsSoFar=" + dbgCallsThisServerTick + " tickCounter=" + tickCounter
                                        + " thread=" + Thread.currentThread().getName());
                    }
                }
            } else {
                dbgLastServerGameTime = gt;
                dbgCallsThisServerTick = 1;
            }
        }

        long gt = (anyLevel != null) ? anyLevel.getGameTime() : Long.MIN_VALUE;
        boolean isNewServerTick = (gt != Long.MIN_VALUE && gt != lastProcessedGameTime);
        if (isNewServerTick) {
            lastProcessedGameTime = gt;
        }

        // Convert incoming JSON into queued steps
        drainActions();

        ServerLevel defaultLevel = null;
        if (anyLevel != null) {
            defaultLevel = anyLevel;
        } else if (net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer() != null) {
            defaultLevel = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer().overworld();
        }

        for (FakeBot bot : bots.values()) {
            if (bot == null)
                continue;

            // If dead/unspawned, respawn into a deterministic level
            if (bot.player == null || !bot.player.isAlive()) {
                if (defaultLevel != null) {
                    respawnBot(bot, defaultLevel);
                }
                continue;
            }

            if (!(bot.player.level() instanceof ServerLevel level))
                continue;

            if (isNewServerTick) {
                applyPendingKnockback(bot.player, level);
                tickHurtIFrames(bot.player);
            }

            if (bot.player instanceof com.example.aiagent.server.DamageableFakePlayer dfp) {
                long now = level.getGameTime();
                if (dfp.lastKnockbackServerTick >= 0 && now <= dfp.lastKnockbackServerTick + 2) {
                    System.out.println("[BOT][DBG][KB-TRACE] now=" + now
                            + " kbTick=" + dfp.lastKnockbackServerTick
                            + " dm=" + bot.player.getDeltaMovement()
                            + " pos=" + bot.player.position()
                            + " stepActive=" + bot.stepActive);
                }
            }

            bot.player.setNoGravity(false);
            bot.player.noPhysics = false;

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

            // If there is no active step, still integrate one physics tick so gravity/knockback/fall happen.
            // No inputs; just advance motion deterministically.
            if (!bot.stepActive) {
                // Clear any movement inputs (so it doesn't keep walking)
                bot.forward = 0.0;
                bot.strafe = 0.0;
                bot.jump = false;
                bot.sprint = false;
                bot.sneak = false;

                // Apply flags to player
                bot.player.setSprinting(false);
                bot.player.setShiftKeyDown(false);
                bot.player.setJumping(false);

                // Advance physics once (gravity, knockback integration) — ONLY once per actual server tick
                if (isNewServerTick) {
                    double prevY = bot.player.getY();
                    boolean prevOnGround = bot.player.onGround();

                    bot.player.travel(Vec3.ZERO);
                    collectNearbyItems(bot, level);
                    updateFallDamage(bot, level, prevY, prevOnGround);
                    sendBotState(level, bot);
                }

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
                bot.strafe = bot.ctrlStrafe;
                bot.jump = bot.ctrlJump;
                bot.sprint = bot.ctrlSprint;
                bot.sneak = bot.ctrlSneak;

                bot.ctrlHoldTicks--;
            } else {
                // No recent control update -> decay only the ctrl deltas.
                // DO NOT wipe bot.forward/strafe/jump here if step payloads are expected to
                // drive them.
                bot.ctrlYawDelta = 0f;
                bot.ctrlPitchDelta = 0f;

                // Optional: if you want full stop when no control packets, uncomment:
                // bot.forward = 0.0;
                // bot.strafe = 0.0;
                // bot.jump = false;
                // bot.sprint = false;
                // bot.sneak = false;
            }

            if (isNewServerTick)
            {
                // 1) apply look/hotbar/attack/use
                applyLookAndHotbarAndActions(bot, level);

                Vec3 preMove = bot.player.getDeltaMovement();
                Vec3 prePos  = bot.player.position();

                // 2) apply movement FOR THIS STEP ONLY
                int kbLock = 0;
                if (bot.player instanceof com.example.aiagent.server.DamageableFakePlayer dfp) {
                    kbLock = dfp.knockbackLockTicks;
                }

                if (kbLock > 0) {
                    // Let vanilla integrate knockback without RL inputs overwriting it
                    bot.player.travel(Vec3.ZERO);

                    if (bot.player instanceof com.example.aiagent.server.DamageableFakePlayer dfp2) {
                        dfp2.knockbackLockTicks = kbLock - 1;
                    }
                } else {
                    applyMovementTravel(bot);
                    collectNearbyItems(bot, level);
                    sendBotState(level, bot);
                }

                Vec3 postMove = bot.player.getDeltaMovement();
                Vec3 postPos  = bot.player.position();

                boolean isKbWindow = false;
                long kbTick = -1;
                if (bot.player instanceof com.example.aiagent.server.DamageableFakePlayer dfp) {
                    kbTick = dfp.lastKnockbackServerTick;
                    isKbWindow = (kbTick >= 0 && level.getGameTime() <= kbTick + 2);
                }

                if (isKbWindow) {
                    System.out.println("[BOT][DBG][KB-MOVE] tick=" + level.getGameTime()
                        + " kbTick=" + kbTick
                        + " preVel=" + preMove + " postVel=" + postMove
                        + " prePos=" + prePos + " postPos=" + postPos
                        + " onGround=" + bot.player.onGround()
                        + " hColl=" + bot.player.horizontalCollision
                        + " vColl=" + bot.player.verticalCollision
                        + " noPhys=" + bot.player.noPhysics);
                }

                // IMPORTANT: snapshot AFTER movement so knockback displacement is visible
                sendBotState(level, bot);

                // Count down (exactly once per tick)
                bot.stepTicksRemaining--;
            }

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

                    if (DEBUG_MOVE)
                        System.out.println("[AI-BOT] COMPLETE seq=" + finishedSeq + " action_id=" + finishedActionId);
                    if (DEBUG_WS)
                        System.out.println("[AI-BOT] ENQUEUE obs+action_result seq=" + finishedSeq + " action_id="
                                + finishedActionId);
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

    // One-time settle so a newly spawned bot starts falling immediately (no "hover tick")
    private void settleSpawnPhysicsOnce(ServerLevel level, ServerPlayer fp) {
        if (fp == null) return;

        // Make sure physics is enabled
        fp.setNoGravity(false);
        fp.noPhysics = false;

        // Clear any weird carry
        fp.setDeltaMovement(Vec3.ZERO);

        // Force "airborne" so gravity integrates on this settle step if needed
        fp.setOnGround(false);

        // Run exactly one vanilla integration step (NO full tick, no baseTick)
        fp.travel(Vec3.ZERO);

        // Make sure the engine treats this as a real motion update
        fp.hasImpulse = true;
    }

    private void applyPendingKnockback(ServerPlayer p, ServerLevel level) {
        if (!(p instanceof DamageableFakePlayer dfp)) return;

        Vec3 imp = dfp.pendingKnockbackImpulse;
        if (imp == null || imp.lengthSqr() <= 1.0e-10) return;

        Vec3 v0 = p.getDeltaMovement();
        Vec3 v1 = v0.add(imp);

        p.setDeltaMovement(v1);
        p.hasImpulse = true;

        System.out.println("[BOT][DBG][KB-APPLY] now=" + level.getGameTime()
                + " kbTick=" + dfp.pendingKnockbackTick
                + " add=" + imp
                + " v0=" + v0
                + " v1=" + v1);

        // clear
        dfp.pendingKnockbackImpulse = Vec3.ZERO;
    }

    private void updateFallDamage(FakeBot bot, ServerLevel level, double prevY, boolean prevOnGround) {
        ServerPlayer p = bot.player;
        if (p == null) return;

        boolean onGroundNow = p.onGround();
        double yNow = p.getY();
        double dy = yNow - prevY;

        // Reset fall accumulation in water / lava / powder snow etc (matches vanilla spirit)
        if (p.isInWaterOrBubble() || p.isInLava()) {
            bot.fallDistanceAcc = 0.0f;
            bot.wasOnGround = onGroundNow;
            bot.lastY = yNow;
            return;
        }

        // Accumulate only when airborne and moving downward
        if (!onGroundNow && dy < 0.0) {
            bot.fallDistanceAcc += (float)(-dy);
        }

        // Landing transition: airborne -> grounded
        if (onGroundNow && !prevOnGround) {
            if (bot.fallDistanceAcc > 0.0f) {
                // Let vanilla compute actual damage (boots, effects, etc.)
                p.causeFallDamage(bot.fallDistanceAcc, 1.0F, level.damageSources().fall());
            }
            bot.fallDistanceAcc = 0.0f;
        }

        bot.wasOnGround = onGroundNow;
        bot.lastY = yNow;
    }

    private void sendBotState(ServerLevel level, FakeBot bot) {
        if (bot.player == null) return;

        final long serverTick = level.getGameTime();

        // IMPORTANT: use serverTick for periodicity (tickCounter can run >1 per server tick)
        final boolean periodic = (serverTick % STATE_SYNC_PERIOD_TICKS) == 0;
        if (!bot.forceStateSync && !periodic) return;

        final boolean wasForced = bot.forceStateSync;
        bot.forceStateSync = false;

        final boolean swingPulse = bot.swingMainHandPulse;

        // TEMP: your old logic (replace with hurtPulseLatch once wired)
        boolean hurtPulse = false;
        if (bot.player instanceof com.example.aiagent.server.DamageableFakePlayer dfp) {
            hurtPulse = dfp.hurtPulseLatch;
        }

        // velocity encoded as blocks/sec (BPS) (keep consistent with your packet)
        final Vec3 velBps = bot.player.getDeltaMovement().scale(20.0);

        final float headYaw = bot.player.getYHeadRot();
        final float bodyYaw = bot.player.yBodyRot;
        final float pitch = bot.player.getXRot();

        final int selectedSlot = bot.player.getInventory().selected;

        final ItemStack mainHand = bot.player.getMainHandItem().copy();
        final ItemStack offHand  = bot.player.getOffhandItem().copy();

        final ItemStack helmet     = bot.player.getItemBySlot(EquipmentSlot.HEAD).copy();
        final ItemStack chestplate = bot.player.getItemBySlot(EquipmentSlot.CHEST).copy();
        final ItemStack leggings   = bot.player.getItemBySlot(EquipmentSlot.LEGS).copy();
        final ItemStack boots      = bot.player.getItemBySlot(EquipmentSlot.FEET).copy();

        S2CBotStatePacket msg = new S2CBotStatePacket(
                bot.botId,
                bot.player.getId(),
                bot.player.getUUID(),
                serverTick,
                bot.player.getX(), bot.player.getY(), bot.player.getZ(),
                velBps.x, velBps.y, velBps.z,
                headYaw, bodyYaw, pitch,
                bot.player.onGround(),
                swingPulse,
                hurtPulse,

                selectedSlot,
                mainHand,
                offHand,
                helmet,
                chestplate,
                leggings,
                boots
        );

        if (DEBUG_NET) {
            System.out.println("[AI-BOT][DBG][SRV-SEND] bot=" + bot.player.getGameProfile().getName()
                    + " tick=" + serverTick
                    + " force=" + wasForced
                    + " headYaw=" + headYaw
                    + " bodyYaw=" + bodyYaw
                    + " pitch=" + pitch
                    + " pos=(" + bot.player.getX() + "," + bot.player.getY() + "," + bot.player.getZ() + ")"
                    + " velBPS=(" + velBps.x + "," + velBps.y + "," + velBps.z + ")"
                    + " onGround=" + bot.player.onGround());
        }

        if (DEBUG_HIT) {
            System.out.println("[AI-BOT][DBG][SRV-SEND] tick=" + level.getGameTime()
                + " bot=" + bot.player.getGameProfile().getName()
                + " hurtPulse=" + hurtPulse
                + " hurtTime=" + bot.player.hurtTime
                + " invuln=" + bot.player.invulnerableTime);
        } 

        // if (DEBUG_KNOCK) {
        //     System.out.println("[BOT][DBG][S2C] tick=" + serverTick
        //         + " bot=" + bot.botId
        //         + " pos=(" + bot.player.getX() + "," + bot.player.getY() + "," + bot.player.getZ() + ")"
        //         + " velDM=" + bot.player.getDeltaMovement()
        //         + " velBPS=(" + velBps.x + "," + velBps.y + "," + velBps.z + ")");
        // }

        BotNet.CHANNEL.send(PacketDistributor.DIMENSION.with(() -> level.dimension()), msg);

        // Clear one-shot pulses after sending
        bot.swingMainHandPulse = false;

        if (bot.player instanceof com.example.aiagent.server.DamageableFakePlayer dfp2) {
            dfp2.hurtPulseLatch = false;
        }
    }

    private void drainActions() {
        String s;
        while ((s = pendingActionJson.poll()) != null) {
            try {
                JsonObject msg = BotMod.GSON.fromJson(s, JsonObject.class);

                // --- NEW: Accept Action v1 envelope format ---
                // { "proto":"1", "kind":"action", "seq":INT, "action_id":"...",
                // "deadline_ms":INT, "payload":{...} }
                if (msg.has("proto") && msg.has("kind")
                        && "1".equals(msg.get("proto").getAsString())
                        && "action".equals(msg.get("kind").getAsString())
                        && msg.has("seq") && msg.has("action_id") && msg.has("payload")) {

                    int seq = msg.get("seq").getAsInt();
                    String actionId = msg.get("action_id").getAsString();
                    int deadlineMs = msg.has("deadline_ms") ? msg.get("deadline_ms").getAsInt() : 50;

                    if (seq < 0)
                        continue;
                    if (actionId == null || actionId.isBlank())
                        continue;
                    if (deadlineMs <= 0)
                        deadlineMs = 50;

                    if (!msg.get("payload").isJsonObject())
                        continue;
                    JsonObject action = msg.getAsJsonObject("payload");

                    int ticks = Math.max(1, (deadlineMs + 49) / 50); // 50ms per tick

                    if (pendingSteps.size() > 200)
                        pendingSteps.poll(); // keep latency bounded
                    pendingSteps.offer(new StepRequest(action, ticks, seq, actionId));

                    if (DEBUG_MOVE) {
                        System.out.println("[DRAIN] queued ENVELOPE step seq=" + seq
                                + " action_id=" + actionId + " ticks=" + ticks
                                + " keys=" + action.keySet());
                    }
                    continue;
                } else if (msg == null)
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
        if (reason != null && !reason.isBlank())
            ar.addProperty("reason", reason);

        ar.addProperty("server_tick", tickCounter);
        ar.addProperty("ts_server", System.currentTimeMillis() / 1000.0);

        JsonObject payload = new JsonObject();
        payload.add("action_result", ar);

        root.add("payload", payload);
        return root;
    }

    private void respawnBot(FakeBot bot, ServerLevel level) {
        if (bot == null || level == null) return;

        // If an old entity reference exists, discard it
        if (bot.player != null) {

            // Save inventory/equipment before discarding old instance
            bot.savedSelected = bot.player.getInventory().selected;
            for (int i = 0; i < 36; i++) {
                bot.savedInv[i] = bot.player.getInventory().getItem(i).copy();
            }
            bot.savedOffhand = bot.player.getOffhandItem().copy();

            // Armor order: FEET, LEGS, CHEST, HEAD
            bot.savedArmor[0] = bot.player.getItemBySlot(EquipmentSlot.FEET).copy();
            bot.savedArmor[1] = bot.player.getItemBySlot(EquipmentSlot.LEGS).copy();
            bot.savedArmor[2] = bot.player.getItemBySlot(EquipmentSlot.CHEST).copy();
            bot.savedArmor[3] = bot.player.getItemBySlot(EquipmentSlot.HEAD).copy();

            bot.player.remove(Entity.RemovalReason.DISCARDED);
            bot.player = null;
        }

        // Spawn fresh instance using same identity
        GameProfile profile = new GameProfile(bot.uuid, bot.name);
        ServerPlayer fp = DamageableFakePlayerFactory.get(level, profile);

        fp.setNoGravity(false);
        fp.noPhysics = false;

        fp.setInvulnerable(false);
        fp.getAbilities().invulnerable = false;
        fp.getAbilities().mayfly = false;
        fp.getAbilities().flying = false;
        fp.onUpdateAbilities();

        fp.gameMode.changeGameModeForPlayer(net.minecraft.world.level.GameType.SURVIVAL);

        // Position: last death pos if present, else world spawn
        BlockPos spawnPos = level.getSharedSpawnPos();
        Vec3 pos = bot.lastDeathPos;
        if (pos == null) {
            pos = new Vec3(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5);
        }

        fp.moveTo(pos.x, pos.y, pos.z, 0f, 0f);
        ensureAddedToWorld(level, fp);
        settleSpawnPhysicsOnce(level, fp);

        bot.player = fp;

        // Force and immediately publish state so ghost doesn't hover / desync
        bot.forceStateSync = true;
        sendBotState(level, bot);

        clearAllControls(bot);
        bot.stepActive = false;
        bot.stepTicksRemaining = 0;
        bot.stepAction = null;
        bot.stepSeq = -1;
        bot.stepActionId = null;
    }


    // --- Observation Builder (Bridge v1) ---
    // Builds an observation payload that ALWAYS includes the required keys:
    // pose, rays, front_clear, world, inventory, collision (+ entities as empty
    // list)

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
        // Use your existing rays if you already compute them; otherwise default to
        // empty array.
        // If your Python schema requires items, empty array is still valid if "rays"
        // itself is required.
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
        } catch (Exception ignored) {
        }

        payload.addProperty("front_clear", frontClear);

        // --------------------
        // world (required)
        // --------------------
        JsonObject world = new JsonObject();
        long dayTime = level.getDayTime() % 24000L;
        world.addProperty("time_of_day", (double) dayTime);

        String weather = "clear";
        if (level.isThundering())
            weather = "thunder";
        else if (level.isRaining())
            weather = "rain";
        world.addProperty("weather", weather);

        String biomeName = "unknown";
        try {
            var biomeKey = level.getBiome(bot.player.blockPosition()).unwrapKey();
            if (biomeKey.isPresent())
                biomeName = biomeKey.get().location().toString();
        } catch (Exception ignored) {
        }
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
            } catch (Exception ignored) {
            }
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

    private boolean equipArmorFromInventoryIndex(ServerPlayer p, int invIndex) {
        if (p == null) return false;

        // Inventory indices: 0..35 (player main inventory+hotbar)
        if (invIndex < 0 || invIndex >= 36) return false;

        ItemStack src = p.getInventory().getItem(invIndex);
        if (src.isEmpty()) return false;

        EquipmentSlot slot = Mob.getEquipmentSlotForItem(src);
        if (slot.getType() != EquipmentSlot.Type.ARMOR) return false;

        ItemStack dst = p.getItemBySlot(slot);

        // Policy: only equip if empty (safe for RL). If you want replace/swap, tell me.
        if (!dst.isEmpty()) return false;

        // Move exactly 1 item
        ItemStack one = src.copy();
        one.setCount(1);

        p.setItemSlot(slot, one);
        src.shrink(1);

        // mark inventory dirty for safety
        p.getInventory().setChanged();
        return true;
    }

    private boolean swapWithSelectedHotbar(ServerPlayer p, int invIndex) {
        if (p == null) return false;

        if (invIndex < 0 || invIndex >= 36) return false;

        int hotbarIndex = p.getInventory().selected; // 0..8
        if (hotbarIndex < 0 || hotbarIndex > 8) return false;

        ItemStack a = p.getInventory().getItem(invIndex);
        ItemStack b = p.getInventory().getItem(hotbarIndex);

        p.getInventory().setItem(invIndex, b);
        p.getInventory().setItem(hotbarIndex, a);
        p.getInventory().setChanged();
        return true;
    }

    private boolean dropFromInventory(ServerPlayer p, ServerLevel level, int invIndex, int count) {
        if (p == null || level == null) return false;
        if (invIndex < 0 || invIndex >= 36) return false;

        ItemStack stack = p.getInventory().getItem(invIndex);
        if (stack.isEmpty()) return false;

        int n = Math.max(1, Math.min(count, stack.getCount()));
        ItemStack drop = stack.copy();
        drop.setCount(n);

        // remove from inv
        stack.shrink(n);
        p.getInventory().setChanged();

        // spawn drop in front of player
        ItemEntity ent = new ItemEntity(level, p.getX(), p.getY() + 0.5, p.getZ(), drop);
        ent.setPickUpDelay(20);
        ent.setDeltaMovement(p.getLookAngle().scale(0.2));
        level.addFreshEntity(ent);
        return true;
    }

    private void applyPayloadToBot(JsonObject payload, FakeBot bot) {
        if (payload == null || bot == null)
            return;

        // -----------------------------
        // LOOK (deltas) accept multiple key spellings
        // -----------------------------
        if (payload.has("look") && payload.get("look").isJsonObject()) {
            JsonObject look = payload.getAsJsonObject("look");

            float dyaw = 0f;
            float dpitch = 0f;

            if (look.has("dYaw"))
                dyaw = look.get("dYaw").getAsFloat();
            else if (look.has("yaw_delta"))
                dyaw = look.get("yaw_delta").getAsFloat();
            else if (look.has("dyaw"))
                dyaw = look.get("dyaw").getAsFloat();

            if (look.has("dPitch"))
                dpitch = look.get("dPitch").getAsFloat();
            else if (look.has("pitch_delta"))
                dpitch = look.get("pitch_delta").getAsFloat();
            else if (look.has("dpitch"))
                dpitch = look.get("dpitch").getAsFloat();

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
            bot.ctrlStrafe = move.has("strafe") ? (float) move.get("strafe").getAsDouble() : 0f;

            // Some senders embed these inside move
            if (move.has("jump"))
                bot.ctrlJump = move.get("jump").getAsBoolean();
            if (move.has("sprint"))
                bot.ctrlSprint = move.get("sprint").getAsBoolean();
            if (move.has("sneak"))
                bot.ctrlSneak = move.get("sneak").getAsBoolean();

            bot.ctrlHoldTicks = Math.max(bot.ctrlHoldTicks, 3);

            if (DEBUG_MOVE) {
                System.out.println("[PAYLOAD MOVE] keys=" + payload.keySet()
                        + " f=" + bot.ctrlForward + " s=" + bot.ctrlStrafe
                        + " jump=" + bot.ctrlJump + " sprint=" + bot.ctrlSprint + " sneak=" + bot.ctrlSneak);
            }
        } else {
            if (DEBUG_MOVE)
                System.out.println("[PAYLOAD NO-MOVE] keys=" + payload.keySet());
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
        bot.use = payload.has("use") && payload.get("use").getAsBoolean();

        // -----------------------------
        // INVENTORY / EQUIP ACTIONS (one-shot)
        // -----------------------------
        if (payload.has("equip_armor_from_slot")) {
            bot.equipArmorFromSlot = payload.get("equip_armor_from_slot").getAsInt();
        }

        if (payload.has("swap_selected_from_slot")) {
            bot.swapMainhandFromSlot = payload.get("swap_selected_from_slot").getAsInt();
        }

        if (payload.has("drop_slot") && payload.get("drop_slot").isJsonObject()) {
            JsonObject d = payload.getAsJsonObject("drop_slot");
            bot.dropFromSlot = d.has("slot") ? d.get("slot").getAsInt() : -1;
            bot.dropCount = d.has("count") ? d.get("count").getAsInt() : 1;
        }

        
    }

    private void applyMovementTravel(FakeBot bot) {
        ServerPlayer p = bot.player;
        if (p == null)
            return;

        // Convert bot inputs to [-1..1]
        float forward = (float) bot.forward;
        float strafe = (float) bot.strafe;

        // deadzone + clamp
        if (Math.abs(forward) < 0.2f)
            forward = 0f;
        if (Math.abs(strafe) < 0.2f)
            strafe = 0f;

        forward = Mth.clamp(forward, -1f, 1f);
        strafe = Mth.clamp(strafe, -1f, 1f);

        // Apply flags
        p.setSprinting(bot.sprint);
        p.setShiftKeyDown(bot.sneak);
        p.setJumping(bot.jump);

        Vec3 pos0 = p.position();
        Vec3 v0 = p.getDeltaMovement();
        double prevY = p.getY();
        boolean prevOnGround = p.onGround();

        p.travel(new Vec3(strafe, 0.0, forward));

        // Apply deterministic fall damage on landing
        if (p.level() instanceof ServerLevel sl) {
            updateFallDamage(bot, sl, prevY, prevOnGround);
        }

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

    private FakeBot getBotByPlayer(ServerPlayer p) {
        if (p == null)
            return null;
        for (FakeBot b : bots.values()) {
            if (b != null && b.player == p)
                return b;
        }
        return null;
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
        // 2.5) INVENTORY / EQUIP (one-shot)
        // -----------------------------
        if (bot.equipArmorFromSlot >= 0) {
            equipArmorFromInventoryIndex(p, bot.equipArmorFromSlot);
        }

        if (bot.swapMainhandFromSlot >= 0) {
            swapWithSelectedHotbar(p, bot.swapMainhandFromSlot);
        }

        if (bot.dropFromSlot >= 0) {
            dropFromInventory(p, level, bot.dropFromSlot, bot.dropCount);
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
            // p.gameMode.useItem(p, level, p.getItemInHand(InteractionHand.MAIN_HAND),
            // InteractionHand.MAIN_HAND);
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

    private boolean tryEquipArmor(ServerPlayer p, ItemStack stack) {
        if (stack.isEmpty()) return false;
        EquipmentSlot slot = Mob.getEquipmentSlotForItem(stack);
        if (slot.getType() != EquipmentSlot.Type.ARMOR) return false;

        ItemStack currently = p.getItemBySlot(slot);
        if (!currently.isEmpty()) return false; // decide your policy: replace or not

        p.setItemSlot(slot, stack.copyWithCount(1));
        stack.shrink(1);
        return true;
    }

    private void collectNearbyItems(FakeBot bot, ServerLevel level) {
        ServerPlayer p = bot.player;
        if (p == null) return;

        // Small radius like vanilla pickup range
        double r = 1.5;
        AABB box = p.getBoundingBox().inflate(r, 0.5, r);

        List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, box, e -> !e.isRemoved() && e.isAlive());
        if (items.isEmpty()) return;

        for (ItemEntity it : items) {
            ItemStack stack = it.getItem();
            if (stack.isEmpty()) continue;

            // Try to add to inventory (vanilla behavior-ish)
            ItemStack leftover = p.getInventory().add(stack) ? ItemStack.EMPTY : stack;

            if (leftover.isEmpty()) {
                it.discard(); // picked up fully
                // Optional: play pickup sound/event
                // level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.2F, 1.0F);
            } else {
                it.setItem(leftover); // partially picked up
            }
        }
    }

    private static void tickHurtIFrames(ServerPlayer p) {
        if (p == null) return;

        if (p.invulnerableTime > 0) p.invulnerableTime--;
        if (p.hurtTime > 0) p.hurtTime--;

        // keep duration consistent if you use it for rendering
        if (p.hurtDuration < p.hurtTime) p.hurtDuration = p.hurtTime;

        // clear the "flash" marker once hurtTime is gone (optional)
        if (p.hurtTime == 0) p.hurtMarked = false;
    }

    /**
     * Ensures the fake player is added to the ServerLevel entity list.
     * If it's already there, do nothing.
     */
    private void ensureAddedToWorld(ServerLevel level, ServerPlayer fp) {
        if (level == null || fp == null) return;

        // If it was removed, it can't be re-added; caller must respawn a fresh instance.
        if (fp.isRemoved()) {
            System.out.println("[AI-BOT][WARN] ensureAddedToWorld called with removed player id=" + fp.getId()
                    + " name=" + fp.getGameProfile().getName() + " — need respawn.");
            return;
        }

        Entity existing = level.getEntity(fp.getId());
        if (existing == fp) return;

        // If something else is using this ID, discard it (prevents duplicates)
        if (existing != null && existing != fp) {
            existing.remove(Entity.RemovalReason.DISCARDED);
        }

        level.addFreshEntity(fp);
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