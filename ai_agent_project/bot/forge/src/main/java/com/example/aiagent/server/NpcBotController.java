package com.example.aiagent.server;

import net.minecraft.server.level.ServerLevel;

/**
 * NpcBotController (SERVER SIDE)
 *
 * Now acts as a thin adapter:
 *  - Receives JSON actions
 *  - Forwards them to FakeBotManager (authoritative FakePlayer)
 *
 * IMPORTANT: No duplicate movement/attack logic here.
 */
public final class NpcBotController {

    private NpcBotController() {}

    public static void enqueueActionJson(String json) {
        if (json == null || json.isBlank()) return;
        ServerBotHooks.BOTS.enqueueActionJson(json);
    }

    public static void tick(ServerLevel level) {
        // No-op for mechanics now; FakeBotManager.tick() is called from ServerBotHooks.
        // Keep this for future visual proxy syncing if you want.
    }
}
