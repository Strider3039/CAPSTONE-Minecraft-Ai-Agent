package com.example.aiagent.server.tests;

import com.example.aiagent.server.FakeBotManager;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerLevel;

public final class BotTestSuite {

    private static boolean enabled = false;
    private static boolean ran = false;

    private BotTestSuite() {}

    public static void enable() {
        enabled = true;
    }

    public static void runOnce(ServerLevel level, FakeBotManager bots) {
        if (!enabled || ran) return;
        ran = true;

        System.out.println("[AI-BOT][TEST] Running BotTestSuite...");

        testTwoStepsBackToBack(bots);
        testDifferentDurations(bots);
        testLookDelta(bots);
        testNoProgress(bots);

    }

    // -----------------------------
    // Tests
    // -----------------------------

    private static void testSingleStepForward(FakeBotManager bots) {
        JsonObject move = new JsonObject();
        move.addProperty("forward", 1.0);

        JsonObject action = new JsonObject();
        action.add("move", move);

        JsonObject step = new JsonObject();
        step.addProperty("cmd", "step");
        step.addProperty("ticks", 20);
        step.addProperty("seq", 999);
        step.addProperty("action_id", "test-forward");
        step.add("action", action);

        bots.enqueueActionJson(step.toString());

        System.out.println("[AI-BOT][TEST] Enqueued test-forward (20 ticks)");
    }

    private static void testTwoStepsBackToBack(FakeBotManager bots) {
        bots.enqueueActionJson(makeStep("test-a", 1001, 20, 1.0, 0.0).toString());
        bots.enqueueActionJson(makeStep("test-b", 1002, 20, 0.0, 1.0).toString());
        System.out.println("[AI-BOT][TEST] Enqueued test-a then test-b");
    }

    private static JsonObject makeStep(String actionId, int seq, int ticks, double forward, double strafe) {
        JsonObject move = new JsonObject();
        move.addProperty("forward", forward);
        move.addProperty("strafe", strafe);

        JsonObject action = new JsonObject();
        action.add("move", move);

        JsonObject step = new JsonObject();
        step.addProperty("cmd", "step");
        step.addProperty("ticks", ticks);
        step.addProperty("seq", seq);
        step.addProperty("action_id", actionId);
        step.add("action", action);
        return step;
    }

    private static void testDifferentDurations(FakeBotManager bots) {
        bots.enqueueActionJson(makeStep("test-20t", 1010, 20, 1.0, 0.0).toString());
        bots.enqueueActionJson(makeStep("test-40t", 1011, 40, 1.0, 0.0).toString());
        System.out.println("[AI-BOT][TEST] Enqueued test-20t and test-40t");
    }

    private static void testLookDelta(FakeBotManager bots) {
        JsonObject look = new JsonObject();
        look.addProperty("dYaw", 10.0);
        look.addProperty("dPitch", 0.0);

        JsonObject action = new JsonObject();
        action.add("look", look);

        JsonObject step = new JsonObject();
        step.addProperty("cmd", "step");
        step.addProperty("ticks", 10);
        step.addProperty("seq", 1020);
        step.addProperty("action_id", "test-look");
        step.add("action", action);

        bots.enqueueActionJson(step.toString());
        System.out.println("[AI-BOT][TEST] Enqueued test-look");
    }

    private static void testNoProgress(FakeBotManager bots) {
        JsonObject action = new JsonObject(); // no move, no look
        JsonObject step = new JsonObject();
        step.addProperty("cmd", "step");
        step.addProperty("ticks", 20);
        step.addProperty("seq", 1030);
        step.addProperty("action_id", "test-noprogress");
        step.add("action", action);

        bots.enqueueActionJson(step.toString());
        System.out.println("[AI-BOT][TEST] Enqueued test-noprogress");
    }

}
