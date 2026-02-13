package com.example.aiagent.server;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;
import com.example.aiagent.server.tests.BotTestSuite;

/**
 * ServerBotHooks (SERVER SIDE)
 *
 * Dedicated server only:
 * - Spawn REAL NPC player once when overworld loads
 * - Run server websocket bridge and feed actions into your manager
 *
 * IMPORTANT:
 * - DO NOT spawn inside PlayerLoggedInEvent (it recurses via placeNewPlayer)
 */
public class ServerBotHooks {

    public static final FakeBotManager BOTS = new FakeBotManager();

    private static final String WS_URI = "ws://127.0.0.1:8765";
    private final ServerBridgeWebSocketClient ws = new ServerBridgeWebSocketClient(WS_URI);

    private boolean spawned = false;

    public ServerBotHooks() {
        MinecraftForge.EVENT_BUS.register(this);
        System.out.println("[AI-BOT] ServerBotHooks registered.");
        System.out.println("[AI-BOT][SERVER-WS] Will connect to " + WS_URI + " (dedicated servers only)");
    }

    private static boolean shouldRun(MinecraftServer server) {
        return server != null && server.isDedicatedServer();
    }

    @SubscribeEvent
    public void onLevelLoad(LevelEvent.Load event) {
        if (spawned) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        MinecraftServer server = level.getServer();
        if (!shouldRun(server)) return;

        // Only overworld
        if (!level.dimension().location().toString().equals("minecraft:overworld")) return;

        System.out.println("[AI-BOT] Dedicated server overworld loaded. Spawning FakePlayer bot...");
        BOTS.ensureDefaultBot(server, level);

        spawned = true;
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        System.out.println("[AI-BOT] Server stopping. Clearing bots...");
        try { ws.closeBlockingSafe(); } catch (Exception ignored) {}

        BOTS.despawnAll();
        spawned = false;
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!spawned) return;

        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();

        // If this is an integrated server (singleplayer), don't run server-side bot obs.
        // In singleplayer we want client-side obs controlling the local player instead.
        if (!server.isDedicatedServer()) {
            return;
        }

        if (!shouldRun(server)) return;

        ServerLevel level = server.overworld();
        if (level == null) return;

        // Run tests once (server-only, opt-in)
        // BotTestSuite.enable();
        // BotTestSuite.runOnce(level, BOTS);

        // Pull WS actions (from python) and enqueue into FakeBotManager
        ws.drainActionsAndApply(level, BOTS);

        // Apply queued actions
        BOTS.tick();
        ws.drainCompletedResultsAndSend(BOTS);

    }

}
