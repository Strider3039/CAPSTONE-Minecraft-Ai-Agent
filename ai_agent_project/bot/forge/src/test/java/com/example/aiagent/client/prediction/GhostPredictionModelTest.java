package com.example.aiagent.client.prediction;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class GhostPredictionModelTest {

    /** Helper to build Truth quickly. Vel is per tick (units/tick), not per second. */
    private static GhostPredictionModel.Truth truth(
            long tick, Vec3 pos, Vec3 velTick, boolean onGround,
            float headYaw, float bodyYaw, float pitch,
            boolean swingPulse) {

        return new GhostPredictionModel.Truth(
                tick, pos, velTick, onGround,
                headYaw, bodyYaw, pitch,
                swingPulse
        );
    }

    @Test
    void verticalVelocity_updatesEveryTick_whenAirborne() {
        GhostPredictionModel m = new GhostPredictionModel();

        // Start airborne, falling steadily. We want to ensure client sim doesn't
        // prematurely clamp vy to 0 before the truth does.
        Vec3 pos = new Vec3(0, 10, 0);
        Vec3 vel = new Vec3(0, -0.08, 0); // falling, per tick

        m.resetFromTruth(truth(0, pos, vel, false, 0, 0, 0, false));

        for (int i = 1; i <= 20; i++) {
            pos = pos.add(vel);

            // make truth vy slowly more negative (accelerating fall)
            vel = new Vec3(0, vel.y - 0.01, 0);

            m.ingestTruth(truth(i, pos, vel, false, 0, 0, 0, false));
            m.stepOneTick();

            // The sim vy should remain negative and roughly track truth (not snap to 0)
            assertTrue(m.sim.velTick.y < -0.001, "sim vy should stay negative while airborne");
            assertTrue(Math.abs(m.sim.velTick.y - vel.y) < 0.15,
                    "sim vy should track truth reasonably (tolerance for reconciliation)");
        }
    }

    @Test
    void groundedY_doesNotClipIntoFloor_afterReconcile() {
        GhostPredictionModel m = new GhostPredictionModel();

        // Truth is onGround at y=0, but sim starts slightly below (clipping).
        m.resetFromTruth(truth(0, new Vec3(0, 0, 0), Vec3.ZERO, true, 0, 0, 0, false));

        // Force sim below ground to simulate a bad frame
        m.sim.pos = new Vec3(0, -0.12, 0);
        m.sim.velTick = new Vec3(0.1, -0.03, 0.0);

        // Next truth: still grounded at y=0
        m.ingestTruth(truth(1, new Vec3(0.1, 0, 0), new Vec3(0.1, 0, 0), true, 0, 0, 0, false));
        m.stepOneTick();

        // This is the key “no floor clipping” assertion.
        assertTrue(m.sim.pos.y >= -1e-6, "sim y should be projected to grounded truth (no sinking)");
        assertEquals(0.0, m.sim.velTick.y, 1e-9, "grounded should kill vertical drift");
    }

    @Test
    void smoothness_constantVelocity_hasNoLargePositionJumps() {
        GhostPredictionModel m = new GhostPredictionModel();

        // Constant forward motion on ground.
        Vec3 pos = new Vec3(0, 0, 0);
        Vec3 vel = new Vec3(0.2, 0.0, 0.0);

        m.resetFromTruth(truth(0, pos, vel, true, 0, 0, 0, false));

        Vec3 lastSimPos = m.sim.pos;

        for (int i = 1; i <= 60; i++) {
            pos = pos.add(vel);

            m.ingestTruth(truth(i, pos, vel, true, 0, 0, 0, false));
            m.stepOneTick();

            Vec3 step = m.sim.pos.subtract(lastSimPos);
            lastSimPos = m.sim.pos;

            // "No big jumps" threshold: tune this to your desired feel
            double jump = step.length();
            assertTrue(jump < 0.5, "sim should not jump wildly at constant velocity; jump=" + jump);
        }
    }

    @Test
    void rotation_changes_propagate_headAndBody() {
        GhostPredictionModel m = new GhostPredictionModel();

        m.resetFromTruth(truth(0, Vec3.ZERO, Vec3.ZERO, true, 0f, 0f, 0f, false));

        // Over 10 ticks, rotate head to 90 degrees, body to 45.
        for (int i = 1; i <= 10; i++) {
            float head = 9f * i;   // 0 -> 90
            float body = 4.5f * i; // 0 -> 45

            m.ingestTruth(truth(i, Vec3.ZERO, Vec3.ZERO, true, head, body, 10f, false));
            m.stepOneTick();
        }

        // We don't require exact equality (smoothing), but we require movement toward targets.
        assertTrue(m.sim.yawHead > 30f, "head yaw should move significantly toward truth");
        assertTrue(m.sim.yawBody > 15f, "body yaw should move significantly toward truth");
        assertTrue(m.sim.pitch > 3f, "pitch should move toward truth");
    }

    @Test
    void animation_swingPulse_isNotDropped() {
        GhostPredictionModel m = new GhostPredictionModel();
        m.resetFromTruth(truth(0, Vec3.ZERO, Vec3.ZERO, true, 0, 0, 0, false));

        // Pulse on tick 3 only
        for (int i = 1; i <= 5; i++) {
            boolean pulse = (i == 3);
            m.ingestTruth(truth(i, Vec3.ZERO, Vec3.ZERO, true, 0, 0, 0, pulse));
            m.stepOneTick();

            if (i == 3) assertTrue(m.sim.swingMainHandPulse, "pulse should appear on tick 3");
            else assertFalse(m.sim.swingMainHandPulse, "no pulse expected on other ticks");
        }
    }
}
