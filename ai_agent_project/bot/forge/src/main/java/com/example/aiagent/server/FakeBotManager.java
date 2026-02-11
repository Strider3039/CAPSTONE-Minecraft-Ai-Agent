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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.network.PacketDistributor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * FakeBotManager (SERVER SIDE)
 *
 * Ghost Architecture version:
 *  - Server spawns & controls FakePlayer (authoritative)
 *  - Server periodically sends AgentState packets for CLIENT-ONLY "ghost render"
 *
 * Key changes vs your file:
 *  - No forced bot.player.tick() (avoid double-ticking)
 *  - Strong "ensure added to world" logic
 *  - Adds spawn/state/despawn packet hooks
 */
public class FakeBotManager {

    // --- Step state machine (FIFO queue) ---
    private static final class StepRequest {
        final JsonObject action;   // action payload (look/move/jump/etc)
        final int ticks;           // duration
        final int seq;             // from Action v1
        final String actionId;     // from Action v1

        StepRequest(JsonObject action, int ticks, int seq, String actionId) {
            this.action = action;
            this.ticks = ticks;
            this.seq = seq;
            this.actionId = actionId;
        }
    }


    private void clearControls(FakeBot bot) {
        bot.forward = 0.0;
        bot.strafe = 0.0;
        bot.jump = false;
        bot.sprint = false;
        bot.sneak = false;
        bot.attack = false;
        bot.use = false;
        bot.selectSlot = -1;
    }


    public ServerPlayer getDefaultPlayerOrNull() {
        FakeBot b = getDefaultBot();
        return (b != null) ? b.player : null;
    }


    public static class FakeBot {
        public final ServerPlayer player;

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

        public FakeBot(ServerPlayer player) {
            this.player = player;
        }
    }

    // Fixed identity (important for ghost tracking)
    private static final UUID DEFAULT_BOT_UUID =
            UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final String DEFAULT_BOT_NAME = "Agent_Dig";

    // How often to send AgentState (in ticks). 2 = 10 updates/sec, 5 = 4 updates/sec.
    private static final int STATE_SYNC_PERIOD_TICKS = 1;

    private final List<FakeBot> bots = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedQueue<String> pendingActionJson = new ConcurrentLinkedQueue<>();

    private final ConcurrentLinkedQueue<StepRequest> pendingSteps = new ConcurrentLinkedQueue<>();
    private long nextStepId = 1;

    private final java.util.concurrent.ConcurrentLinkedQueue<com.google.gson.JsonObject> completedStepResults =
    new java.util.concurrent.ConcurrentLinkedQueue<>();

    private int tickCounter = 0;

    public java.util.concurrent.ConcurrentLinkedQueue<com.google.gson.JsonObject> getCompletedStepResultsQueue() {
        return completedStepResults;
    }


    public List<FakeBot> getAllBots() { return bots; }

    public JsonObject pollCompletedResult() {
        return completedStepResults.poll();
    }


    public void enqueueActionJson(String json) {
        if (json == null || json.isEmpty()) return;
        pendingActionJson.offer(json);
    }

    /** Old signature kept for compatibility. Finds overworld and spawns there. */
    public void ensureDefaultBot(MinecraftServer server) {
        if (server == null) return;
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

        // Player-like behavior
        fp.setInvulnerable(false);

        fp.getAbilities().invulnerable = false;
        fp.getAbilities().mayfly = false;
        fp.getAbilities().flying = false;
        fp.getAbilities().setWalkingSpeed(0.1f);
        fp.onUpdateAbilities();

        fp.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);

        // Place at spawn (one-time)
        BlockPos spawn = level.getSharedSpawnPos();
        fp.moveTo(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, 0f, 0f);

        ensureAddedToWorld(level, fp);

        FakeBot bot = new FakeBot(fp);
        bots.add(bot);

        System.out.println("[AI-BOT] Default FakeBot spawned: " + DEFAULT_BOT_NAME + " at " + spawn
                + " in " + level.dimension().location());
    }

    /**
     * Called every server tick:
     *  - drains queued actions (latest wins)
     *  - applies control state to FakePlayer(s)
     *  - periodically syncs state to clients for ghost rendering
     */
    public void tick() {
        tickCounter++;

        // Convert incoming JSON into queued steps
        drainActions();

        for (FakeBot bot : bots) {
            if (bot == null || bot.player == null) continue;
            if (!bot.player.isAlive()) continue;
            if (!(bot.player.level() instanceof ServerLevel level)) continue;

            bot.player.setNoGravity(false);
            bot.player.noPhysics = false;

            // --- State sync to clients for ghost rendering ---
            if ((tickCounter % STATE_SYNC_PERIOD_TICKS) == 0) {
                S2CBotStatePacket msg = new S2CBotStatePacket(
                        "agent0",
                        bot.player.getX(), bot.player.getY(), bot.player.getZ(),
                        bot.player.getYRot(), bot.player.getXRot(),
                        bot.player.onGround()
                );
                BotNet.CHANNEL.send(PacketDistributor.DIMENSION.with(() -> level.dimension()), msg);
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

                    clearControls(bot);
                }
            }

            if (bot.stepActive) {
                // Invariant: stepActive must always have valid correlation fields
                if (bot.stepSeq < 0 || bot.stepActionId == null || bot.stepActionId.isBlank()) {
                    System.out.println("[AI-BOT] WARNING: stepActive but missing seq/action_id; forcing reset. "
                            + "name=" + bot.player.getGameProfile().getName()
                            + " stepSeq=" + bot.stepSeq
                            + " actionId=" + bot.stepActionId);

                    clearControls(bot);
                    bot.stepActive = false;
                    bot.stepTicksRemaining = 0;
                    bot.stepAction = null;
                    bot.stepSeq = -1;
                    bot.stepActionId = null;
                    continue;
                }

                // Apply the step action each tick
                applyPayloadToBot(bot.stepAction, bot);

                // Drive the player based on current control fields
                applyControl(bot, level);

                // Count down and finish cleanly
                bot.stepTicksRemaining--;

                if (bot.stepTicksRemaining <= 0) {
                    // Capture correlation BEFORE reset
                    int finishedSeq = bot.stepSeq;
                    String finishedActionId = bot.stepActionId;

                    // Guard (should never fail now, but keep it anyway)
                    if (finishedSeq < 0 || finishedActionId == null || finishedActionId.isBlank()) {
                        System.out.println("[AI-BOT] ERROR: invalid finishedSeq/actionId at finish; skipping emit. "
                                + "name=" + bot.player.getGameProfile().getName()
                                + " finishedSeq=" + finishedSeq
                                + " actionId=" + finishedActionId);
                    } else {
                        ServerPlayer p = bot.player;

                        // -----------------------------
                        // Build OBSERVATION (schema-valid minimal)
                        // -----------------------------
                        JsonObject obsMsg = new JsonObject();
                        obsMsg.addProperty("proto", "1");
                        obsMsg.addProperty("kind", "observation");
                        obsMsg.addProperty("seq", finishedSeq);
                        obsMsg.addProperty("timestamp", System.currentTimeMillis() / 1000.0);

                        JsonObject obsPayload = new JsonObject();

                        // pose
                        JsonObject pose = new JsonObject();
                        pose.addProperty("x", p.getX());
                        pose.addProperty("y", p.getY());
                        pose.addProperty("z", p.getZ());
                        pose.addProperty("yaw", p.getYRot());
                        pose.addProperty("pitch", p.getXRot());
                        obsPayload.add("pose", pose);

                        // rays (send empty if you aren't attaching them here)
                        obsPayload.add("rays", new com.google.gson.JsonArray());

                        // REQUIRED: front_clear (placeholder is OK for now)
                        obsPayload.addProperty("front_clear", true);

                        // REQUIRED: world
                        JsonObject world = new JsonObject();
                        world.addProperty("time_of_day", (double) (level.getDayTime() % 24000L));
                        world.addProperty("weather", level.isRaining() ? "rain" : "clear");
                        world.addProperty(
                                "biome",
                                level.getBiome(p.blockPosition()).unwrapKey()
                                        .map(k -> k.location().toString())
                                        .orElse("unknown")
                        );
                        obsPayload.add("world", world);

                        // REQUIRED: inventory (selected_slot + hotbar)
                        JsonObject inv = new JsonObject();
                        inv.addProperty("selected_slot", p.getInventory().selected);

                        com.google.gson.JsonArray hotbar = new com.google.gson.JsonArray();
                        for (int i = 0; i < 9; i++) {
                            JsonObject item = new JsonObject();
                            var stack = p.getInventory().getItem(i);
                            item.addProperty("id", stack.isEmpty() ? "air" : stack.getItem().toString());
                            item.addProperty("count", stack.isEmpty() ? 0 : stack.getCount());
                            hotbar.add(item);
                        }
                        inv.add("hotbar", hotbar);
                        obsPayload.add("inventory", inv);

                        // REQUIRED: collision (is_grounded, is_colliding, no_progress)
                        boolean grounded = p.onGround();
                        boolean colliding = p.horizontalCollision || p.verticalCollision;

                        double dx = p.getX() - bot.stepStartX;
                        double dy = p.getY() - bot.stepStartY;
                        double dz = p.getZ() - bot.stepStartZ;
                        double dist2 = dx * dx + dy * dy + dz * dz;
                        boolean noProgress = dist2 < 1e-4;

                        JsonObject collision = new JsonObject();
                        collision.addProperty("is_grounded", grounded);
                        collision.addProperty("is_colliding", colliding);
                        collision.addProperty("no_progress", noProgress);
                        obsPayload.add("collision", collision);

                        obsMsg.add("payload", obsPayload);

                        // -----------------------------
                        // Build ACTION_RESULT
                        // -----------------------------
                        JsonObject arMsg = new JsonObject();
                        arMsg.addProperty("proto", "1");
                        arMsg.addProperty("kind", "action_result");
                        arMsg.addProperty("seq", finishedSeq);
                        arMsg.addProperty("timestamp", System.currentTimeMillis() / 1000.0);

                        JsonObject arPayload = new JsonObject();
                        JsonObject ar = new JsonObject();
                        ar.addProperty("action_id", finishedActionId);
                        ar.addProperty("status", "success");
                        arPayload.add("action_result", ar);
                        arMsg.add("payload", arPayload);

                        System.out.println("[AI-BOT] FINISH step seq=" + finishedSeq
                                + " action_id=" + finishedActionId
                                + " name=" + bot.player.getGameProfile().getName());

                        completedStepResults.offer(obsMsg);
                        completedStepResults.offer(arMsg);
                    }

                    // Reset bot state
                    clearControls(bot);
                    bot.stepActive = false;
                    bot.stepTicksRemaining = 0;
                    bot.stepAction = null;

                    bot.stepSeq = -1;
                    bot.stepActionId = null;
                }
            }
        }
    }



    private void drainActions() {
        String s;
        while ((s = pendingActionJson.poll()) != null) {
            try {
                JsonObject msg = BotMod.GSON.fromJson(s, JsonObject.class);
                if (msg == null) continue;

                // Internal step format (created by ServerBridgeWebSocketClient):
                // { "cmd":"step", "ticks":N, "seq":INT, "action_id":"...", "action":{...} }
                if (msg.has("cmd") && "step".equals(msg.get("cmd").getAsString())
                        && msg.has("ticks") && msg.has("action")
                        && msg.has("seq") && msg.has("action_id")) {

                    int ticks = msg.get("ticks").getAsInt();
                    if (ticks <= 0) ticks = 1;

                    int seq = msg.get("seq").getAsInt();
                    String actionId = msg.get("action_id").getAsString();
                    if (actionId == null || actionId.isBlank()) continue;

                    JsonObject action = msg.getAsJsonObject("action");
                    if (action == null) continue;

                    pendingSteps.offer(new StepRequest(action, ticks, seq, actionId));
                    continue;
                }

                // Back-compat: if someone enqueues raw payload, treat as 1-tick anonymous step
                pendingSteps.offer(new StepRequest(msg, 1, -1, "anon-" + (nextStepId++)));

            } catch (Exception ignored) {}
        }
    }


    private FakeBot getDefaultBot() {
        for (FakeBot b : bots) {
            if (b.player != null && DEFAULT_BOT_UUID.equals(b.player.getUUID())) {
                return b;
            }
        }
        return null;
    }

    private void applyPayloadToBot(JsonObject payload, FakeBot bot) {
        if (payload == null || bot == null) return;

        // LOOK (deltas)
        if (payload.has("look")) {
            JsonObject look = payload.getAsJsonObject("look");
            float dYaw = look.has("dYaw") ? look.get("dYaw").getAsFloat() : 0f;
            float dPitch = look.has("dPitch") ? look.get("dPitch").getAsFloat() : 0f;

            bot.yaw += dYaw;
            bot.pitch += dPitch;

            if (bot.pitch > 89f) bot.pitch = 89f;
            if (bot.pitch < -89f) bot.pitch = -89f;
        }

        // MOVE
        if (payload.has("move")) {
            JsonObject move = payload.getAsJsonObject("move");
            bot.forward = move.has("forward") ? move.get("forward").getAsDouble() : 0.0;
            bot.strafe  = move.has("strafe")  ? move.get("strafe").getAsDouble()  : 0.0;
        } else {
            bot.forward = 0.0;
            bot.strafe = 0.0;
        }

        bot.jump   = payload.has("jump")   && payload.get("jump").getAsBoolean();
        bot.sprint = payload.has("sprint") && payload.get("sprint").getAsBoolean();
        bot.sneak  = payload.has("sneak")  && payload.get("sneak").getAsBoolean();

        if (payload.has("select_slot")) {
            bot.selectSlot = payload.get("select_slot").getAsInt();
        }

        bot.attack = payload.has("attack") && payload.get("attack").getAsBoolean();
        bot.use    = payload.has("use")    && payload.get("use").getAsBoolean();
    }

    private void applyControl(FakeBot bot, ServerLevel level) {
        ServerPlayer p = bot.player;

        // -----------------------------
        // 1) LOOK (authoritative)
        // -----------------------------
        // Clamp pitch (safety)
        if (bot.pitch > 89f) bot.pitch = 89f;
        if (bot.pitch < -89f) bot.pitch = -89f;

        p.setYRot(bot.yaw);
        p.setXRot(bot.pitch);

        // Head yaw matters for a lot of "player-like" behavior
        p.setYHeadRot(bot.yaw);

        // Previous-frame fields (helps interpolation / some logic that uses old values)
        p.yRotO = bot.yaw;
        p.xRotO = bot.pitch;
        p.yHeadRotO = bot.yaw;

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
        // 4) MOVE (set lateral velocity, preserve gravity)
        // -----------------------------
        double f = bot.forward;
        double s = bot.strafe;

        // deadzone + clamp
        if (Math.abs(f) < 0.2) f = 0.0;
        if (Math.abs(s) < 0.2) s = 0.0;

        f = Math.max(-1.0, Math.min(1.0, f));
        s = Math.max(-1.0, Math.min(1.0, s));

        p.setJumping(bot.jump);

        // Convert strafe/forward into world-space using yaw
        float yawRad = (float) Math.toRadians(p.getYRot());
        double sin = Math.sin(yawRad);
        double cos = Math.cos(yawRad);

        // Minecraft forward is -Z in local space; using (forward=f, strafe=s):
        double dx = (f * -sin) + (s * cos);
        double dz = (f *  cos) + (s * sin);

        // Normalize so diagonal isn’t faster
        double mag = Math.sqrt(dx*dx + dz*dz);
        if (mag > 1e-6) {
            dx /= mag;
            dz /= mag;
        }

        // Speed: tune these
        double baseSpeed = bot.sprint ? 0.15 : 0.10; // blocks/tick-ish
        dx *= baseSpeed;
        dz *= baseSpeed;

        // Preserve current Y velocity so gravity works normally
        Vec3 v = p.getDeltaMovement();
        p.setDeltaMovement(dx, v.y, dz);

        // Optional: helps when you manually set delta movement
        p.hurtMarked = true;

        // -----------------------------
        // 5) ATTACK (edge-trigger)
        // -----------------------------
        if (bot.attack && !bot.lastAttack) {
            doServerAttack(level, p);
        }
        bot.lastAttack = bot.attack;

        // -----------------------------
        // 6) USE (edge-trigger)
        // -----------------------------
        if (bot.use && !bot.lastUse) {
            p.swing(InteractionHand.MAIN_HAND);
            // Later (real right-click):
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
        if (level == null || fp == null) return;
        if (fp.isRemoved()) return;

        if (level.getEntity(fp.getId()) == null) {
            level.addFreshEntity(fp);
        }
    }



    public void despawnAll() {
        for (FakeBot b : bots) {
            if (b == null || b.player == null) continue;

            try {

            } catch (Exception ignored) {}

            try {
                b.player.discard();
            } catch (Exception ignored) {}
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

        // If these fields are accessible in your mappings, use them. If not, fallback false.
        boolean isColliding = false;
        try {
            isColliding = p.horizontalCollision || p.verticalCollision;
        } catch (Throwable ignored) {}

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
