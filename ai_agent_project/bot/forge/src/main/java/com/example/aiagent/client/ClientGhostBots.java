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

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * ClientGhostBots (CLIENT SIDE)
 *
 * Tick-driven smoothing (20Hz) + vanilla render interpolation.
 *
 * Key design:
 *  - S2C packets ONLY update target state (no ghost movement on packet arrival)
 *  - ClientTickEvent END runs smoothing at fixed dt=1/20
 *  - We write xo/yo/zo + x/y/z each client tick so vanilla interpolation works cleanly
 *
 * Register ClientGhostBots::onClientTick on the Forge EVENT_BUS from client bootstrap.
 */
public final class ClientGhostBots {

    private static final Map<String, RemotePlayer> ghosts = new HashMap<>();
    private static final Map<String, Deque<Snapshot>> snapQueues = new HashMap<>();
    private static ClientLevel lastLevel;

    // Client-side estimate of current server tick time (as a double so we can add partialTicks)
    private static double serverTickNowEstimate = 0.0;

    // Interpolation delay in server ticks (2 = 100ms at 20 TPS; 3 = 150ms)
    private static final double DELAY_TICKS = 2.0;

    private static boolean serverTickInitialized = false;

    // Snapshot buffer sizing
    private static final int MAX_SNAPSHOTS = 64;

    private ClientGhostBots() {}

    // ===== Packet ingestion =====

    public static void onBotState(S2CBotStatePacket s) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;

        // Clear cached ghosts when switching worlds/servers/dimensions
        if (lastLevel != level) {
            ghosts.clear();
            snapQueues.clear();
            lastLevel = level;
            serverTickNowEstimate = 0.0;
            serverTickInitialized = false;
            System.out.println("[AI-BOT][CLIENT] Level changed, cleared ghosts.");
        }

        RemotePlayer ghost = ghosts.get(s.botId());

        if (ghost == null) {
            ghost = spawnGhost(level, s);
            ghosts.put(s.botId(), ghost);
        }
        // Ensure snapshot queue exists
        Deque<Snapshot> q = snapQueues.computeIfAbsent(s.botId(), k -> new ArrayDeque<>());

        // Build snapshot (serverTick is REQUIRED)
        long tick = s.serverTick();

        // If you haven’t added velocity to the packet yet, use Vec3.ZERO for now.
        // If you DID add it, use new Vec3(s.vx(), s.vy(), s.vz()).
        Vec3 pos = new Vec3(s.x(), s.y(), s.z());
        Vec3 vel = new Vec3(s.vx(), s.vy(), s.vz());

        Snapshot snap = new Snapshot(
                tick,
                pos,
                vel,
                s.yaw(),
                s.pitch(),
                s.onGround()
        );

        // Remove any existing snapshot with the same tick (handle rare duplicates)
        if (!q.isEmpty() && q.getLast().tick == tick) {
            q.removeLast();
        }

        // Insert snapshot in-order (handle rare out-of-order)
        if (!q.isEmpty() && tick <= q.getLast().tick) {
            insertSorted(q, snap);
        } else {
            q.addLast(snap);
        }

        // Cap queue size
        while (q.size() > MAX_SNAPSHOTS) {
            q.removeFirst();
        }

        // World change reset safety: initialize server tick estimate on first packet arrival
        if (!serverTickInitialized) {
            serverTickNowEstimate = tick;
            serverTickInitialized = true;
        }

        // One-shot animation pulse
        if (s.swingMainHandPulse()) {
            ghost.swing(InteractionHand.MAIN_HAND);
        }

    }

    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;

        // Level swap safety
        if (lastLevel != level) return;

        double partial = mc.getFrameTime(); // 0..1
        double serverNow = serverTickNowEstimate + partial;
        double renderTick = serverNow - DELAY_TICKS;

        for (Map.Entry<String, RemotePlayer> e : ghosts.entrySet()) {
            String botId = e.getKey();
            RemotePlayer ghost = e.getValue();
            Deque<Snapshot> q = snapQueues.get(botId);
            if (ghost == null || q == null || q.size() < 2) continue;

            Bracket b = findBracket(q, renderTick);
            if (b == null) continue;

            Pose p = interpolatePose(b.a, b.b, renderTick);

            // IMPORTANT: Disable vanilla interpolation by setting old==new each frame
            ghost.xo = p.pos.x;
            ghost.yo = p.pos.y;
            ghost.zo = p.pos.z;

            ghost.setPos(p.pos.x, p.pos.y, p.pos.z);

            ghost.yRotO = p.yaw;
            ghost.xRotO = p.pitch;

            ghost.setYRot(p.yaw);
            ghost.setXRot(p.pitch);

            // Head yaw
            ghost.setYHeadRot(p.yaw);
            try { ghost.yHeadRotO = p.yaw; } catch (Throwable ignored) {}

            // Body yaw (CRITICAL for player model head turning)
            ghost.yBodyRot = p.yaw;
            ghost.yBodyRotO = p.yaw;

            ghost.setOnGround(p.onGround);
        }
    }

    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.isPaused()) return;

        // Level swap safety
        if (lastLevel != level) {
            ghosts.clear();
            snapQueues.clear();
            lastLevel = level;
            serverTickNowEstimate = 0.0;
            serverTickInitialized = false;
            return;
        }

        // Advance estimate by exactly 1 tick per client tick
        serverTickNowEstimate += 1.0;

        // Soft-correct drift toward newest snapshot tick (low gain!)
        long newest = newestSnapshotTick();
        if (newest != Long.MIN_VALUE) {
            double err = newest - serverTickNowEstimate;
            serverTickNowEstimate += err * 0.02; // low gain drift correction
        }
    }

    private static final class Bracket {
        final Snapshot a;
        final Snapshot b;
        Bracket(Snapshot a, Snapshot b) { this.a = a; this.b = b; }
    }

    private static final class Pose {
        final Vec3 pos;
        final float yaw;
        final float pitch;
        final boolean onGround;

        Pose(Vec3 pos, float yaw, float pitch, boolean onGround) {
            this.pos = pos;
            this.yaw = yaw;
            this.pitch = pitch;
            this.onGround = onGround;
        }
    }

    private static Bracket findBracket(Deque<Snapshot> q, double renderTick) {
        // Drop snapshots that are too old to ever be used again
        while (q.size() >= 2) {
            Snapshot first = q.peekFirst();
            Snapshot second = q.stream().skip(1).findFirst().orElse(null);
            if (second == null) break;

            // Only drop the first snapshot if the *second* is also strictly before renderTick.
            if (second.tick < renderTick) {
                q.removeFirst();
            } else {
                break;
            }
        }
        if (q.size() < 2) return null;

        Snapshot a = q.peekFirst();
        Snapshot b = null;

        for (Snapshot s : q) {
            if (s.tick <= renderTick) a = s;
            if (s.tick >= renderTick) { b = s; break; }
        }

        if (b == null) {
            // renderTick is newer than newest snapshot (we’re missing data)
            // You can choose to return null or extrapolate slightly.
            return null;
        }

        if (a == b) {
            // Need two distinct snapshots
            Snapshot next = null;
            boolean found = false;
            for (Snapshot s : q) {
                if (found) { next = s; break; }
                if (s == a) found = true;
            }
            if (next == null) return null;
            b = next;
        }

        return new Bracket(a, b);
    }

    private static Pose interpolatePose(Snapshot a, Snapshot b, double renderTick) {
        double span = (double)(b.tick - a.tick);
        if (span <= 0.0) {
            return new Pose(a.pos, a.yaw, a.pitch, a.onGround);
        }

        double t = (renderTick - a.tick) / span;
        t = Mth.clamp((float)t, 0.0f, 1.0f);

        // Position (linear for now)
        // Position (Hermite, using server velocities) for smooth acceleration
        double dtSeconds = (b.tick - a.tick) / 20.0;

        // Guard: if tick span is weird or landing state toggles, fall back to linear
        Vec3 pos;
        if (dtSeconds <= 0.0 || a.onGround != b.onGround) {
            pos = new Vec3(
                    Mth.lerp(t, a.pos.x, b.pos.x),
                    Mth.lerp(t, a.pos.y, b.pos.y),
                    Mth.lerp(t, a.pos.z, b.pos.z)
            );
        } else {
            double tt = t;
            double tt2 = tt * tt;
            double tt3 = tt2 * tt;

            double h00 =  2.0 * tt3 - 3.0 * tt2 + 1.0;
            double h10 =        tt3 - 2.0 * tt2 + tt;
            double h01 = -2.0 * tt3 + 3.0 * tt2;
            double h11 =        tt3 -       tt2;

            Vec3 p0 = a.pos;
            Vec3 p1 = b.pos;

            Vec3 v0 = a.vel;
            Vec3 v1 = b.vel;

            // If on ground, kill vertical tangent to avoid ground buzzing
            if (a.onGround) v0 = new Vec3(v0.x, 0.0, v0.z);
            if (b.onGround) v1 = new Vec3(v1.x, 0.0, v1.z);

            // Tangents are velocity scaled by dtSeconds
            Vec3 m0 = a.vel.scale(dtSeconds);
            Vec3 m1 = b.vel.scale(dtSeconds);

            pos = p0.scale(h00)
                    .add(m0.scale(h10))
                    .add(p1.scale(h01))
                    .add(m1.scale(h11));
        }

        // Rotation (shortest path yaw)
        float yaw = a.yaw + Mth.wrapDegrees(b.yaw - a.yaw) * (float)t;
        float pitch = (float) Mth.lerp(t, a.pitch, b.pitch);

        // Ground flag: choose closer snapshot’s state
        boolean onGround = (t < 0.5) ? a.onGround : b.onGround;

        return new Pose(pos, yaw, pitch, onGround);
    }

    private static long newestSnapshotTick() {
        long newest = Long.MIN_VALUE;
        for (Deque<Snapshot> q : snapQueues.values()) {
            if (q != null && !q.isEmpty()) {
                newest = Math.max(newest, q.getLast().tick);
            }
        }
        return newest;
    }

    private static void insertSorted(Deque<Snapshot> q, Snapshot s) {
        // q is small (<=64), so O(n) insertion is fine.
        ArrayDeque<Snapshot> tmp = new ArrayDeque<>(q.size() + 1);

        boolean inserted = false;
        while (!q.isEmpty()) {
            Snapshot cur = q.removeFirst();
            if (!inserted && s.tick < cur.tick) {
                tmp.addLast(s);
                inserted = true;
            }
            tmp.addLast(cur);
        }
        if (!inserted) tmp.addLast(s);

        q.addAll(tmp);
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
        try {
            ghost.yHeadRotO = s.yaw();
        } catch (Throwable ignored) {}

        // If an entity with this ID already exists, remove it first (prevents duplicates)
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

    private static final class Snapshot {
        final long tick;
        final Vec3 pos;
        final Vec3 vel;      // if you don’t send vel yet, set Vec3.ZERO
        final float yaw;
        final float pitch;
        final boolean onGround;

        Snapshot(long tick, Vec3 pos, Vec3 vel, float yaw, float pitch, boolean onGround) {
            this.tick = tick;
            this.pos = pos;
            this.vel = vel;
            this.yaw = yaw;
            this.pitch = pitch;
            this.onGround = onGround;
        }
    }
}