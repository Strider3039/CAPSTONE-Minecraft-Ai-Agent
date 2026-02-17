package com.example.aiagent.client.prediction;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/**
 * Pure deterministic prediction/reconciliation model.
 * No Minecraft Entity calls. Unit-testable.
 *
 * Timebase: 20Hz ticks. (One call to stepOneTick() == 1 server tick)
 */
public final class GhostPredictionModel {

    /** Authoritative snapshot from server. */
    public static final class Truth {
        public final long serverTick;
        public final Vec3 pos;
        public final Vec3 velTick;   // units per tick (NOT per second)
        public final boolean onGround;
        public final float yawHead;
        public final float yawBody;
        public final float pitch;
        public final boolean swingMainHandPulse;

        public Truth(long serverTick, Vec3 pos, Vec3 velTick, boolean onGround,
                     float yawHead, float yawBody, float pitch,
                     boolean swingMainHandPulse) {
            this.serverTick = serverTick;
            this.pos = pos;
            this.velTick = velTick;
            this.onGround = onGround;
            this.yawHead = yawHead;
            this.yawBody = yawBody;
            this.pitch = pitch;
            this.swingMainHandPulse = swingMainHandPulse;
        }
    }

    /** Current simulated state (what client uses to render). */
    public static final class SimState {
        public Vec3 pos = Vec3.ZERO;
        public Vec3 velTick = Vec3.ZERO;
        public boolean onGround = true;
        public float yawHead = 0f;
        public float yawBody = 0f;
        public float pitch = 0f;

        // Event-like outputs
        public boolean swingMainHandPulse = false;
    }

    // --- Tunables (start from what you already have) ---
    private static final double KP_POS = 0.25;
    private static final double KD_VEL = 0.20;

    // clamp to avoid correction jitter
    private static final double MAX_CORR_XZ_PER_TICK = 0.15;

    // Treat vertical as stricter to prevent floor clipping
    private static final boolean GROUNDED_SNAP_Y = true;

    private Optional<Truth> lastTruth = Optional.empty();

    public final SimState sim = new SimState();

    public void resetFromTruth(Truth t) {
        lastTruth = Optional.of(t);
        sim.pos = t.pos;
        sim.velTick = t.velTick;
        sim.onGround = t.onGround;
        sim.yawHead = t.yawHead;
        sim.yawBody = t.yawBody;
        sim.pitch = t.pitch;
        sim.swingMainHandPulse = false;
    }

    public void ingestTruth(Truth t) {
        lastTruth = Optional.of(t);

        // Optionally: if this is the very first truth we see, initialize
        if (sim.pos == Vec3.ZERO && sim.velTick == Vec3.ZERO) {
            resetFromTruth(t);
        }
    }

    /** Advance one 20Hz tick of prediction. */
    public void stepOneTick() {
        if (lastTruth.isEmpty()) return;
        Truth t = lastTruth.get();

        // 1) emit event pulses (unit tests can assert these aren't dropped)
        sim.swingMainHandPulse = t.swingMainHandPulse;

        // 2) integrate predicted motion (simple Euler; match your current style)
        sim.pos = sim.pos.add(sim.velTick);

        // 3) reconcile toward truth (XZ PD + vertical grounding policy)
        reconcileToTruth(t);

        // 4) rotation smoothing (head/body + pitch)
        smoothRotations(t);
    }

    private void reconcileToTruth(Truth t) {
        Vec3 ePos = t.pos.subtract(sim.pos);
        Vec3 eVel = t.velTick.subtract(sim.velTick);

        // XZ-only PD
        Vec3 ePosXZ = new Vec3(ePos.x, 0, ePos.z);
        Vec3 eVelXZ = new Vec3(eVel.x, 0, eVel.z);
        Vec3 corrXZ = ePosXZ.scale(KP_POS).add(eVelXZ.scale(KD_VEL));

        double mag = corrXZ.length();
        if (mag > MAX_CORR_XZ_PER_TICK) {
            corrXZ = corrXZ.scale(MAX_CORR_XZ_PER_TICK / mag);
        }

        sim.velTick = new Vec3(
            sim.velTick.x + corrXZ.x,
            sim.velTick.y,
            sim.velTick.z + corrXZ.z
        );

        // Vertical handling
        if (t.onGround) {
            sim.onGround = true;

            if (GROUNDED_SNAP_Y) {
                sim.pos = new Vec3(sim.pos.x, t.pos.y, sim.pos.z);
            } else {
                // softer projection option
                double dy = t.pos.y - sim.pos.y;
                sim.pos = new Vec3(sim.pos.x, sim.pos.y + dy * 0.35, sim.pos.z);
            }

            // kill vertical drift when grounded
            sim.velTick = new Vec3(sim.velTick.x, 0.0, sim.velTick.z);
        } else {
            sim.onGround = false;

            // Let vertical velocity reconcile softly (no hard clamp)
            double vyCorr = (t.velTick.y - sim.velTick.y) * 0.25;
            sim.velTick = new Vec3(sim.velTick.x, sim.velTick.y + vyCorr, sim.velTick.z);
        }
    }

    private void smoothRotations(Truth t) {
        // Head
        float headErr = Mth.wrapDegrees(t.yawHead - sim.yawHead);
        sim.yawHead += headErr * 0.60f;

        // Body
        float bodyErr = Mth.wrapDegrees(t.yawBody - sim.yawBody);
        sim.yawBody += bodyErr * 0.45f;

        // Pitch
        float pitchErr = t.pitch - sim.pitch;
        sim.pitch += pitchErr * 0.60f;

        sim.pitch = Mth.clamp(sim.pitch, -90f, 90f);
    }
}
