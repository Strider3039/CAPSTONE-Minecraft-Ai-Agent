package com.example.aiagent.server;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

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

    private final FakeBotManager bots;

    /** Bridge WebSocket URI. Override with JVM arg: -Dai_agent.bridge_uri=ws://host:port */
    private static final String DEFAULT_WS_URI = "ws://127.0.0.1:8765";
    private static String getBridgeUri() {
        String u = System.getProperty("ai_agent.bridge_uri");
        return (u != null && !u.isBlank()) ? u.trim() : DEFAULT_WS_URI;
    }

    private final ServerBridgeWebSocketClient ws = new ServerBridgeWebSocketClient(getBridgeUri());

    private static final java.util.concurrent.atomic.AtomicInteger INSTANCES =
        new java.util.concurrent.atomic.AtomicInteger(0);

    private final int instanceId = INSTANCES.incrementAndGet();

    private boolean spawned = false;
    private boolean DEBUG_WS = false;

    public ServerBotHooks(FakeBotManager bots) {
        this.bots = bots;
        com.example.aiagent.BotMod.getInstance().setBridgeClient(this.ws);

        MinecraftForge.EVENT_BUS.register(this);

        String uri = getBridgeUri();
        System.out.println("[AI-BOT] ServerBotHooks registered. instanceId=" + instanceId
                + " this=" + System.identityHashCode(this));
        System.out.println("[AI-BOT][SERVER-WS] Will connect to " + uri + " (dedicated servers only)");
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
        bots.ensureDefaultBot(server, level);

        spawned = true;
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        System.out.println("[AI-BOT] Server stopping. Clearing bots...");
        try { ws.closeBlockingSafe(); } catch (Exception ignored) {}

        bots.despawnAll();
        spawned = false;
    }

    @SubscribeEvent
    public void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        if (!spawned) return;
        if (!(event.level instanceof ServerLevel level)) return;

        MinecraftServer server = level.getServer();
        if (!shouldRun(server)) return;

        // Only overworld (match spawn rule)
        if (!level.dimension().location().toString().equals("minecraft:overworld")) return;

        if (DEBUG_WS) {
            System.out.println("[AI-BOT][DBG][HOOK] onLevelTick(START) instanceId=" + instanceId
                    + " this=" + System.identityHashCode(this)
                    + " gt=" + level.getGameTime()
                    + " thread=" + Thread.currentThread().getName());
        }

        // Pull WS actions (from python) and enqueue into FakeBotManager
        ws.drainActionsAndApply(level, bots);

        // Apply queued actions + physics at the correct tick phase
        bots.tick();

        // Send results back
        ws.drainCompletedResultsAndSend(bots);
    }

}
