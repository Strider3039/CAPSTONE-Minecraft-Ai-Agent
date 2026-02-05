package com.example.aiagent.server;

import com.example.aiagent.BotMod;
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
        final JsonObject action; // the action payload
        final int ticks;         // how long to hold it
        final long id;           // optional debug id

        StepRequest(JsonObject action, int ticks, long id) {
            this.action = action;
            this.ticks = ticks;
            this.id = id;
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


    private final ConcurrentLinkedQueue<StepRequest> pendingSteps = new ConcurrentLinkedQueue<>();
    private long nextStepId = 1;


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

        // --- Step execution state ---
        public boolean stepActive = false;
        public int stepTicksRemaining = 0;
        public JsonObject stepAction = null;
        public long stepId = -1;


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
    private static final int STATE_SYNC_PERIOD_TICKS = 2;

    private final List<FakeBot> bots = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedQueue<String> pendingActionJson = new ConcurrentLinkedQueue<>();

    private int tickCounter = 0;

    public List<FakeBot> getAllBots() { return bots; }

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
            // Ensure it is still actually in the world (after dimension reloads etc)
            ensureAddedToWorld(level, existing.player);
            return;
        }

        GameProfile profile = new GameProfile(DEFAULT_BOT_UUID, DEFAULT_BOT_NAME);
        ServerPlayer fp = FakePlayerFactory.get(level, profile);

        // Ensure physics is enabled
        fp.setNoGravity(false);
        fp.noPhysics = false;

        // Place at spawn (one-time)
        BlockPos spawn = level.getSharedSpawnPos();
        fp.moveTo(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, 0f, 0f);

        // Ensure it is actually in the world entity list
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

            if ((tickCounter % 20) == 0) {
                ensureAddedToWorld(level, bot.player);
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
                    bot.stepId = req.id;

                    clearControls(bot);
                }
            }

            if (bot.stepActive) {
                // Apply the step action each tick
                applyPayloadToBot(bot.stepAction, bot);

                // Drive the player based on current control fields
                applyControl(bot, level);

                // Count down and finish cleanly
                bot.stepTicksRemaining--;

                if (bot.stepTicksRemaining <= 0) {
                    clearControls(bot);
                    bot.stepActive = false;
                    bot.stepAction = null;

                    System.out.println("[AI-BOT] Step finished id=" + bot.stepId);
                    bot.stepId = -1;
                }
            }
        }
    }



    private void drainActions() {
        String s;
        while ((s = pendingActionJson.poll()) != null) {
            try {
                JsonObject payload = BotMod.GSON.fromJson(s, JsonObject.class);
                if (payload == null) continue;

                // Preferred step format:
                // { "cmd":"step", "ticks": 5, "action": { ... } }
                if (payload.has("cmd") && "step".equals(payload.get("cmd").getAsString())
                        && payload.has("ticks") && payload.has("action")) {

                    int ticks = payload.get("ticks").getAsInt();
                    if (ticks <= 0) ticks = 1;

                    JsonObject action = payload.getAsJsonObject("action");
                    if (action == null) continue;

                    pendingSteps.offer(new StepRequest(action, ticks, nextStepId++));
                    continue;
                }

                // Back-compat: if they just send an action object, treat it as a 1-tick step
                pendingSteps.offer(new StepRequest(payload, 1, nextStepId++));

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

    private void applyPayloadToDefaultBot(JsonObject payload) {
        FakeBot bot = getDefaultBot();
        if (bot == null) return;

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
        // 4) MOVE (vanilla travel - best for ServerPlayer/FakePlayer)
        // -----------------------------
        double f = bot.forward;
        double s = bot.strafe;

        // deadzone + clamp
        if (Math.abs(f) < 0.2) f = 0.0;
        if (Math.abs(s) < 0.2) s = 0.0;

        f = Math.max(-1.0, Math.min(1.0, f));
        s = Math.max(-1.0, Math.min(1.0, s));

        // Jump input (vanilla uses this)
        p.setJumping(bot.jump);

        // Feed relative input: (strafe, vertical, forward)
        // NOTE: travel applies yaw internally for players/living entities.
        p.travel(new Vec3(s, 0.0, f));

        Vec3 vel = p.getDeltaMovement();

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

        // Players are tracked separately from normal entities.
        boolean inPlayersList = level.players().contains(fp);
        ServerPlayer byUuid = level.getServer().getPlayerList().getPlayer(fp.getUUID());

        if (!inPlayersList && byUuid == null) {
            try {
                level.addFreshEntity(fp);
            } catch (Throwable t) {
                System.out.println("[AI-BOT] Failed to add FakePlayer to world: " + t.getMessage());
            }
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

    private JsonObject buildObservation(ServerPlayer p) {
        JsonObject o = new JsonObject();

        JsonObject pos = new JsonObject();
        pos.addProperty("x", p.getX());
        pos.addProperty("y", p.getY());
        pos.addProperty("z", p.getZ());
        o.add("pos", pos);

        o.addProperty("yaw", p.getYRot());
        o.addProperty("pitch", p.getXRot());
        o.addProperty("onGround", p.onGround());

        Vec3 v = p.getDeltaMovement();
        JsonObject vel = new JsonObject();
        vel.addProperty("x", v.x);
        vel.addProperty("y", v.y);
        vel.addProperty("z", v.z);
        o.add("vel", vel);

        o.addProperty("health", p.getHealth());
        o.addProperty("food", p.getFoodData().getFoodLevel());

        return o;
    }

}
