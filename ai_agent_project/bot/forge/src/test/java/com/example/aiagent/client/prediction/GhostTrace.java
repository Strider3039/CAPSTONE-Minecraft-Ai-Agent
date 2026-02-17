package com.example.aiagent.client.prediction;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

public final class GhostTrace {

    public static final class Row {
        public final long tick;
        public final Vec3 truthPos, simPos;
        public final Vec3 truthVel, simVel;
        public final boolean truthGround, simGround;
        public final float truthHeadYaw, simHeadYaw;
        public final float truthBodyYaw, simBodyYaw;
        public final float truthPitch, simPitch;
        public final boolean swingPulse;

        public Row(long tick,
                   Vec3 truthPos, Vec3 simPos,
                   Vec3 truthVel, Vec3 simVel,
                   boolean truthGround, boolean simGround,
                   float truthHeadYaw, float simHeadYaw,
                   float truthBodyYaw, float simBodyYaw,
                   float truthPitch, float simPitch,
                   boolean swingPulse) {
            this.tick = tick;
            this.truthPos = truthPos;
            this.simPos = simPos;
            this.truthVel = truthVel;
            this.simVel = simVel;
            this.truthGround = truthGround;
            this.simGround = simGround;
            this.truthHeadYaw = truthHeadYaw;
            this.simHeadYaw = simHeadYaw;
            this.truthBodyYaw = truthBodyYaw;
            this.simBodyYaw = simBodyYaw;
            this.truthPitch = truthPitch;
            this.simPitch = simPitch;
            this.swingPulse = swingPulse;
        }
    }

    private final List<Row> rows = new ArrayList<>();

    public void add(long tick, GhostPredictionModel.Truth truth, GhostPredictionModel.SimState sim) {
        rows.add(new Row(
                tick,
                truth.pos, sim.pos,
                truth.velTick, sim.velTick,
                truth.onGround, sim.onGround,
                truth.yawHead, sim.yawHead,
                truth.yawBody, sim.yawBody,
                truth.pitch, sim.pitch,
                sim.swingMainHandPulse
        ));
    }

    public List<String> toCsvLines() {
        List<String> out = new ArrayList<>();
        out.add(String.join(",",
                "tick",
                "truth_x","truth_y","truth_z",
                "sim_x","sim_y","sim_z",
                "posErr",
                "truth_vx","truth_vy","truth_vz",
                "sim_vx","sim_vy","sim_vz",
                "velErr",
                "truthGround","simGround",
                "truthHeadYaw","simHeadYaw","headYawErr",
                "truthBodyYaw","simBodyYaw","bodyYawErr",
                "truthPitch","simPitch","pitchErr",
                "swingPulse"
        ));

        for (Row r : rows) {
            double posErr = r.truthPos.subtract(r.simPos).length();
            double velErr = r.truthVel.subtract(r.simVel).length();

            float headErr = Mth.wrapDegrees(r.truthHeadYaw - r.simHeadYaw);
            float bodyErr = Mth.wrapDegrees(r.truthBodyYaw - r.simBodyYaw);
            float pitchErr = (r.truthPitch - r.simPitch);

            out.add(String.format(java.util.Locale.US,
                    "%d,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%s,%s,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%s",
                    r.tick,
                    r.truthPos.x, r.truthPos.y, r.truthPos.z,
                    r.simPos.x, r.simPos.y, r.simPos.z,
                    posErr,
                    r.truthVel.x, r.truthVel.y, r.truthVel.z,
                    r.simVel.x, r.simVel.y, r.simVel.z,
                    velErr,
                    Boolean.toString(r.truthGround), Boolean.toString(r.simGround),
                    r.truthHeadYaw, r.simHeadYaw, headErr,
                    r.truthBodyYaw, r.simBodyYaw, bodyErr,
                    r.truthPitch, r.simPitch, pitchErr,
                    Boolean.toString(r.swingPulse)
            ));
        }
        return out;
    }

    public List<String> toLogSummary(String testName) {
        double maxPosErr = 0, maxVelErr = 0;
        double maxYErr = 0;
        int swingCount = 0;

        for (Row r : rows) {
            double posErr = r.truthPos.subtract(r.simPos).length();
            double velErr = r.truthVel.subtract(r.simVel).length();
            maxPosErr = Math.max(maxPosErr, posErr);
            maxVelErr = Math.max(maxVelErr, velErr);
            maxYErr = Math.max(maxYErr, Math.abs(r.truthPos.y - r.simPos.y));
            if (r.swingPulse) swingCount++;
        }

        List<String> out = new ArrayList<>();
        out.add("TEST: " + testName);
        out.add("rows=" + rows.size());
        out.add(String.format(java.util.Locale.US, "maxPosErr=%.6f", maxPosErr));
        out.add(String.format(java.util.Locale.US, "maxVelErr=%.6f", maxVelErr));
        out.add(String.format(java.util.Locale.US, "maxAbsYErr=%.6f", maxYErr));
        out.add("swingPulseCount=" + swingCount);
        out.add("");
        out.add("Notes:");
        out.add("- This report is for polishing: failures are expected early.");
        out.add("- Open the CSV to inspect drift and any spikes.");
        return out;
    }

    public double[] seriesPosErr() {
        double[] s = new double[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            s[i] = r.truthPos.subtract(r.simPos).length();
        }
        return s;
    }

    public double[] seriesYErr() {
        double[] s = new double[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            s[i] = r.truthPos.y - r.simPos.y;
        }
        return s;
    }

    public double[] seriesVy() {
        double[] s = new double[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            s[i] = rows.get(i).simVel.y;
        }
        return s;
    }
}
