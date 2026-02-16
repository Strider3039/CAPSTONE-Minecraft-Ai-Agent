package com.example.aiagent.client;

import com.example.aiagent.net.S2CBotStatePacket;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.registries.ForgeRegistry.Snapshot;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.jline.reader.impl.DefaultParser.Bracket;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * ClientGhostBots (CLIENT SIDE)
 *
 * Tick-driven smoothing (20Hz) + vanilla render interpolation.
 *
 * Key design:
 * - S2C packets ONLY update target state (no ghost movement on packet arrival)
 * - ClientTickEvent END runs smoothing at fixed dt=1/20
 * - We write xo/yo/zo + x/y/z each client tick so vanilla interpolation works
 * cleanly
 *
 * Register ClientGhostBots::onClientTick on the Forge EVENT_BUS from client
 * bootstrap.
 */
public final class ClientGhostBots {

    private static final Map<String, RemotePlayer> ghosts = new HashMap<>();
    private static final Map<String, ServerTruth> truthByBot = new HashMap<>();
    private static final Map<String, GhostSim> simByBot = new HashMap<>();
    private static ClientLevel lastLevel;

    // Debug telemetry
    private static final boolean DEBUG_GHOST = true;
    private static final int DEBUG_EVERY_TICKS = 10; // print once per bot every N client ticks
    private static long debugClientTickCounter = 0;
    private static final Map<String, Long> lastSeenTickByBot = new HashMap<>();

    private static final class DebugStats {
        long lastPrintTick = -1;

        // rolling counters since last print
        int snaps = 0;
        int packets = 0;
        int groundMismatch = 0;
        int bigCorrClamp = 0;

        // last known
        long lastServerTick = -1;
        double lastPosErr = 0;
        double lastVelErr = 0;
        double lastCorrMag = 0;
        boolean lastTruthGround = false;
        boolean lastSimGround = false;
    }

    private static final Map<String, DebugStats> dbgByBot = new HashMap<>();

    private static final double TPS = 20.0;

    // Client-side physics constants (vanilla-ish)
    private static final double GRAVITY_PER_TICK = -0.08; // blocks/tick^2
    private static final double DRAG_AIR = 0.98; // per tick

    // PD correction tuning (visual)
    private static final double KP_POS = 0.28; // position stiffness per tick
    private static final double KD_VEL = 0.55; // velocity damping per tick
    private static final double MAX_CORR_PER_TICK = 0.35; // clamp correction accel-like term (blocks/tick)

    // Hard snap thresholds (authority)
    private static final double SNAP_POS_ERR = 6.0; // blocks

    private ClientGhostBots() {
    }

    // ===== Packet ingestion =====

    public static void onBotState(S2CBotStatePacket s) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null)
            return;

        // DEBUG: detect duplicate tick deliveries for this bot
        // (use your truth map OR last tick seen map; simplest is a standalone map)
        Long prevTick = lastSeenTickByBot.put(s.botId(), s.serverTick());
        if (prevTick != null && prevTick.longValue() == s.serverTick()) {
            System.out.println("[AI-BOT][DBG][CL-DUP] bot=" + s.botId()
                    + " serverTick=" + s.serverTick()
                    + " (duplicate delivery)");
        }

        // Clear cached ghosts when switching worlds/servers/dimensions
        if (lastLevel != level) {
            ghosts.clear();
            truthByBot.clear();
            simByBot.clear();
            lastLevel = level;
            System.out.println("[AI-BOT][CLIENT] Level changed, cleared ghosts.");
        }

        RemotePlayer ghost = ghosts.get(s.botId());
        if (ghost == null) {
            ghost = spawnGhost(level, s);
            ghosts.put(s.botId(), ghost);
        }

        // Packet velocities appear to be blocks/sec currently -> convert to blocks/tick
        Vec3 pos = new Vec3(s.x(), s.y(), s.z());
        Vec3 velTick = new Vec3(s.vx(), s.vy(), s.vz()).scale(1.0 / TPS);

        if (s.onGround()) {
            velTick = new Vec3(velTick.x, 0.0, velTick.z);
        }

        ServerTruth truth = new ServerTruth(
                s.serverTick(),
                pos,
                velTick,
                s.yaw(),
                s.pitch(),
                s.onGround());

        DebugStats dbg = dbgByBot.computeIfAbsent(s.botId(), k -> new DebugStats());
        dbg.packets++;

        if (dbg.lastServerTick != -1) {
            if (s.serverTick() < dbg.lastServerTick) {
                System.out.println("[AI-BOT][DBG][PKT] bot=" + s.botId()
                        + " NON_MONO_SERVER_TICK prev=" + dbg.lastServerTick + " now=" + s.serverTick());
            } else if (s.serverTick() == dbg.lastServerTick) {
                System.out.println("[AI-BOT][DBG][PKT] bot=" + s.botId()
                        + " DUP_SERVER_TICK tick=" + s.serverTick());
            }
        }
        dbg.lastServerTick = s.serverTick();

        truthByBot.put(s.botId(), truth);

        // Initialize sim state on first packet (hard snap init)
        GhostSim sim = simByBot.get(s.botId());
        if (sim == null) {
            sim = new GhostSim(truth);
            dbg.snaps++;
            System.out.println("[AI-BOT][DBG][INIT] bot=" + s.botId()
                    + " snap-init tick=" + s.serverTick()
                    + " pos=" + fmtVec(truth.pos)
                    + " velTick=" + fmtVec(truth.velTick)
                    + " yaw=" + truth.yawHead + " pitch=" + truth.pitch
                    + " onGround=" + truth.onGround);
            simByBot.put(s.botId(), sim);

            // Apply immediately so the entity exists at correct place/rot before first tick
            applySimToEntity(ghost, sim);
        }

        // One-shot animation pulse
        if (s.swingMainHandPulse()) {
            ghost.swing(InteractionHand.MAIN_HAND);
        }
    }

    public static void onClientTick(TickEvent.ClientTickEvent event) {

        debugClientTickCounter++;
        if (event.phase != TickEvent.Phase.END)
            return;

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.isPaused())
            return;

        // Level swap safety
        if (lastLevel != level) {
            ghosts.clear();
            truthByBot.clear();
            simByBot.clear();
            lastLevel = level;
            return;
        }

        for (Map.Entry<String, RemotePlayer> e : ghosts.entrySet()) {
            String botId = e.getKey();
            RemotePlayer ghost = e.getValue();
            if (ghost == null)
                continue;

            ServerTruth truth = truthByBot.get(botId);
            GhostSim sim = simByBot.get(botId);
            if (truth == null || sim == null)
                continue;

            // Save prev tick values for vanilla interpolation
            sim.prevPos = sim.pos;
            sim.prevYawHead = sim.yawHead;
            sim.prevYawBody = sim.yawBody;
            sim.prevPitch = sim.pitch;

            // Authority discontinuity handling (teleports / big drift)
            double posErr = truth.pos.subtract(sim.pos).length();

            DebugStats dbg = dbgByBot.computeIfAbsent(botId, k -> new DebugStats());
            dbg.lastTruthGround = truth.onGround;
            dbg.lastSimGround = sim.onGround;

            // Pre-step error
            Vec3 ePos0 = truth.pos.subtract(sim.pos);
            Vec3 eVel0 = truth.velTick.subtract(sim.velTick);
            double posErr0 = ePos0.length();
            double velErr0 = eVel0.length();

            if (posErr > SNAP_POS_ERR) {
                hardSnapToTruth(sim, truth);
                applySimToEntity(ghost, sim);
                continue;
            }

            // 1) Predict one tick locally
            predictOneTick(sim);

            // 2) Smoothly correct toward server truth (PD controller)
            double corrMag = correctTowardTruth(sim, truth);
            if (corrMag > MAX_CORR_PER_TICK)
                dbg.bigCorrClamp++;
            dbg.lastCorrMag = corrMag;

            Vec3 ePos1 = truth.pos.subtract(sim.pos);
            Vec3 eVel1 = truth.velTick.subtract(sim.velTick);
            double posErr1 = ePos1.length();
            double velErr1 = eVel1.length();

            dbg.lastPosErr = posErr1;
            dbg.lastVelErr = velErr1;

            if (truth.onGround != sim.onGround)
                dbg.groundMismatch++;

            // 3) Smooth yaw/head/body like vanilla-ish expectations
            updateYaw(sim, truth);

            // 4) Apply to entity with tick-delta fields for vanilla render interpolation
            applySimTickDeltaToEntity(ghost, sim);

            if (DEBUG_GHOST && (debugClientTickCounter % DEBUG_EVERY_TICKS == 0)) {
                // Only print once per N ticks per bot
                if (dbg.lastPrintTick != debugClientTickCounter) {
                    dbg.lastPrintTick = debugClientTickCounter;

                    double dy = truth.pos.y - sim.pos.y;

                    System.out.println("[AI-BOT][DBG][SIM] bot=" + botId
                            + " st=" + truth.tick
                            + " posErr=" + fmt(posErr1)
                            + " velErr=" + fmt(velErr1)
                            + " simPos=" + fmtVec(sim.pos)
                            + " truthPos=" + fmtVec(truth.pos)
                            + " simVel=" + fmtVec(sim.velTick)
                            + " truthVel=" + fmtVec(truth.velTick)
                            + " dy=" + fmt(dy)
                            + " g(sim/truth)=" + sim.onGround + "/" + truth.onGround
                            + " snaps=" + dbg.snaps
                            + " pkt=" + dbg.packets
                            + " gMis=" + dbg.groundMismatch
                            + " clamp=" + dbg.bigCorrClamp);

                    // reset rolling counters so each print is “per window”
                    dbg.groundMismatch = 0;
                    dbg.bigCorrClamp = 0;
                }
            }
        }

        if (DEBUG_GHOST) {
            // optional: print a single bot’s error, but don’t spam in production
        }
    }

    private static void applySimTickDeltaToEntity(RemotePlayer ghost, GhostSim sim) {
        // Prev tick values (vanilla uses these to interpolate + animate)
        ghost.xo = sim.prevPos.x;
        ghost.yo = sim.prevPos.y;
        ghost.zo = sim.prevPos.z;

        ghost.yRotO = sim.prevYawBody;
        ghost.xRotO = sim.prevPitch;

        try {
            ghost.yHeadRotO = sim.prevYawHead;
        } catch (Throwable ignored) {
        }
        try {
            ghost.yBodyRotO = sim.prevYawBody;
        } catch (Throwable ignored) {
        }

        // Current tick values
        ghost.setPos(sim.pos.x, sim.pos.y, sim.pos.z);
        ghost.setYRot(sim.yawBody);
        ghost.setXRot(sim.pitch);
        ghost.setYHeadRot(sim.yawHead);
        try {
            ghost.yBodyRot = sim.yawBody;
        } catch (Throwable ignored) {
        }

        ghost.setOnGround(sim.onGround);
        ghost.setDeltaMovement(sim.velTick); // helps animation/motion expectations
    }

    private static void applySimToEntity(RemotePlayer ghost, GhostSim sim) {
        // Used for initialization or hard snaps
        ghost.xo = sim.pos.x;
        ghost.yo = sim.pos.y;
        ghost.zo = sim.pos.z;

        ghost.setPos(sim.pos.x, sim.pos.y, sim.pos.z);
        ghost.setYRot(sim.yawBody);
        ghost.setXRot(sim.pitch);
        ghost.setYHeadRot(sim.yawHead);
        try {
            ghost.yBodyRot = sim.yawBody;
        } catch (Throwable ignored) {
        }

        ghost.setOnGround(sim.onGround);
        ghost.setDeltaMovement(sim.velTick);
    }

    private static void predictOneTick(GhostSim sim) {
        double vy = sim.velTick.y;

        if (sim.onGround) {
            // Grounded: do not integrate gravity at all (until we add collision-based
            // stepping).
            vy = 0.0;
        } else {
            vy = (vy * DRAG_AIR) + GRAVITY_PER_TICK;
        }

        sim.velTick = new Vec3(
                sim.velTick.x * DRAG_AIR,
                vy,
                sim.velTick.z * DRAG_AIR);

        // Integrate position (no collision resolution yet; you can upgrade later)
        sim.pos = sim.pos.add(sim.velTick);

        // NOTE: without collision, sim.onGround can only drift via server correction.
        // For now, bias onGround toward existing state; truth correction will fix it.
    }

    private static double correctTowardTruth(GhostSim sim, ServerTruth truth) {
        Vec3 ePos = truth.pos.subtract(sim.pos);
        Vec3 eVel = truth.velTick.subtract(sim.velTick);

        Vec3 corr = ePos.scale(KP_POS).add(eVel.scale(KD_VEL));

        double mag = corr.length();
        if (mag > MAX_CORR_PER_TICK) {
            corr = corr.scale(MAX_CORR_PER_TICK / mag);
        }

        sim.velTick = sim.velTick.add(corr);

        // Ground manifold constraint:
        // If server says grounded, we must not "float" below forever (no collision sim
        // yet).
        if (truth.onGround) {
            sim.onGround = true;

            double dy = truth.pos.y - sim.pos.y;

            // Critically-damped positional projection in Y (no snap)
            // 0.35 is aggressive enough to converge in a few ticks but not teleport.
            sim.pos = new Vec3(sim.pos.x, sim.pos.y + dy * 0.35, sim.pos.z);

            // Kill vertical velocity when grounded (server authority)
            sim.velTick = new Vec3(sim.velTick.x, 0.0, sim.velTick.z);

        } else {
            // Airborne: allow sim to be airborne; don't instantly force onGround false
            // unless close
            // to prevent flutter at edges.
            if (sim.onGround) {
                double dy = Math.abs(truth.pos.y - sim.pos.y);
                if (dy > 0.6)
                    sim.onGround = false; // hysteresis band
            } else {
                sim.onGround = false;
            }
        }

        return mag;
    }

    private static void updateYaw(GhostSim sim, ServerTruth truth) {
        // Head tracks truth faster
        float headErr = Mth.wrapDegrees(truth.yawHead - sim.yawHead);
        sim.yawHead = sim.yawHead + headErr * 0.40f;

        // Pitch tracks truth
        float pitchErr = truth.pitch - sim.pitch;
        sim.pitch = sim.pitch + pitchErr * 0.40f;

        // Body follows head with clamp per tick (vanilla-ish)
        float targetBody = sim.yawHead;

        float maxStep = 12.0f; // deg/tick
        float d = Mth.wrapDegrees(targetBody - sim.yawBody);
        d = Mth.clamp(d, -maxStep, maxStep);
        sim.yawBody = sim.yawBody + d;

        // Clamp head relative to body (prevents exorcist turns)
        float headDelta = Mth.wrapDegrees(sim.yawHead - sim.yawBody);
        headDelta = Mth.clamp(headDelta, -75f, 75f);
        sim.yawHead = sim.yawBody + headDelta;
    }

    private static void hardSnapToTruth(GhostSim sim, ServerTruth truth) {
        sim.pos = truth.pos;
        sim.velTick = truth.velTick;
        sim.onGround = truth.onGround;

        sim.yawHead = truth.yawHead;
        sim.yawBody = truth.yawHead;
        sim.pitch = truth.pitch;

        sim.prevPos = truth.pos;
        sim.prevYawHead = truth.yawHead;
        sim.prevYawBody = truth.yawHead;
        sim.prevPitch = truth.pitch;
        sim.lastTruthTick = truth.tick;
    }

    private static RemotePlayer spawnGhost(ClientLevel level, S2CBotStatePacket s) {
        UUID uuid = UUID.nameUUIDFromBytes(("bot:" + s.botId()).getBytes(StandardCharsets.UTF_8));
        GameProfile profile = new GameProfile(uuid, s.botId());

        RemotePlayer ghost = new RemotePlayer(level, profile);

        int entityId = stableEntityId(s.botId());
        ghost.setId(entityId);

        ghost.setPos(s.x(), s.y(), s.z());
        ghost.setYRot(s.yaw());
        ghost.setXRot(s.pitch());
        ghost.setYHeadRot(s.yaw());
        ghost.setDeltaMovement(new Vec3(s.vx(), s.vy(), s.vz()).scale(1.0 / TPS));
        try {
            ghost.yHeadRotO = s.yaw();
        } catch (Throwable ignored) {
        }

        // If an entity with this ID already exists, remove it first (prevents
        // duplicates)
        Entity existing = level.getEntity(entityId);
        if (existing != null) {
            existing.remove(RemovalReason.DISCARDED);
        }

        // IMPORTANT: Player entities must be added with addPlayer (not addEntity)
        level.addPlayer(entityId, ghost);

        return ghost;
    }

    private static int stableEntityId(String botId) {
        return 0x3FFF0000 ^ botId.hashCode();
    }

    private static final class ServerTruth {
        long tick;
        Vec3 pos; // blocks
        Vec3 velTick; // blocks/tick
        float yawHead;
        float pitch;
        boolean onGround;

        ServerTruth(long tick, Vec3 pos, Vec3 velTick, float yawHead, float pitch, boolean onGround) {
            this.tick = tick;
            this.pos = pos;
            this.velTick = velTick;
            this.yawHead = yawHead;
            this.pitch = pitch;
            this.onGround = onGround;
        }
    }

    private static final class GhostSim {
        Vec3 pos; // blocks
        Vec3 velTick; // blocks/tick
        boolean onGround;

        float yawHead; // degrees
        float yawBody; // degrees
        float pitch; // degrees

        // prev tick values for vanilla interpolation
        Vec3 prevPos;
        float prevYawHead;
        float prevYawBody;
        float prevPitch;

        long lastTruthTick;

        GhostSim(ServerTruth t) {
            this.pos = t.pos;
            this.velTick = t.velTick;
            this.onGround = t.onGround;

            this.yawHead = t.yawHead;
            this.yawBody = t.yawHead;
            this.pitch = t.pitch;

            this.prevPos = t.pos;
            this.prevYawHead = t.yawHead;
            this.prevYawBody = t.yawHead;
            this.prevPitch = t.pitch;

            this.lastTruthTick = t.tick;
        }
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.US, "%.3f", v);
    }

    private static String fmtVec(Vec3 v) {
        return "(" + fmt(v.x) + "," + fmt(v.y) + "," + fmt(v.z) + ")";
    }
}