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
    void report_fallAndLand_generatesArtifacts() throws Exception {
        GhostPredictionModel m = new GhostPredictionModel();
        GhostTrace trace = new GhostTrace();

        // Start airborne, then land at y=0
        Vec3 pos = new Vec3(0, 5, 0);
        Vec3 vel = new Vec3(0.1, -0.06, 0.0);

        m.resetFromTruth(truth(0, pos, vel, false, 0, 0, 0, false));

        boolean landed = false;

        for (int i = 1; i <= 80; i++) {
            // simple scripted truth physics for the test:
            // accelerate downward until y <= 0, then land
            if (!landed) {
                vel = new Vec3(vel.x, vel.y - 0.01, vel.z);
                pos = pos.add(vel);

                if (pos.y <= 0.0) {
                    pos = new Vec3(pos.x, 0.0, pos.z);
                    vel = new Vec3(vel.x, 0.0, vel.z);
                    landed = true;
                }
            } else {
                // after landing, keep moving flat
                pos = pos.add(new Vec3(0.1, 0.0, 0.0));
                vel = new Vec3(0.1, 0.0, 0.0);
            }

            boolean onGround = landed;

            // rotate over time (so we can visualize yaw tracking)
            float headYaw = i * 2.0f;
            float bodyYaw = i * 1.0f;
            float pitch = 10.0f;

            boolean swingPulse = (i % 20 == 0);

            GhostPredictionModel.Truth t = truth(i, pos, vel, onGround, headYaw, bodyYaw, pitch, swingPulse);

            m.ingestTruth(t);
            m.stepOneTick();
            trace.add(i, t, m.sim);
        }

        String base = "fallAndLand_" + TestReportWriter.timestampTag();

        // Write log + csv
        TestReportWriter.writeLog(base, trace.toLogSummary("report_fallAndLand_generatesArtifacts"));
        TestReportWriter.writeCsv(base, trace.toCsvLines());

        // Write a quick PNG chart: posErr, yErr, simVy
        TestReportWriter.writePngChart(
                base,
                "Ghost Prediction Report: Fall & Land",
                1100,
                550,
                trace.seriesPosErr(), "posErr",
                trace.seriesYErr(), "yErr (truth - sim)",
                trace.seriesVy(), "simVy"
        );

        // This test can be "non-strict" initially. You can add asserts later.
        assertTrue(true);
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
