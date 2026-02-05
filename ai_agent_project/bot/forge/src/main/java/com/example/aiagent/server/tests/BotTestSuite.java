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

        testSingleStepForward(bots);
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
}
