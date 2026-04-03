package com.example.aiagent.server;

import com.example.aiagent.BridgeUriResolver;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * ServerBotHooks (SERVER SIDE)
 *
 * Logical server side:
 * - Spawn REAL NPC player once when overworld loads
 * - Run server websocket bridge and feed actions into your manager
 *
 * IMPORTANT:
 * - DO NOT spawn inside PlayerLoggedInEvent (it recurses via placeNewPlayer)
 */
public class ServerBotHooks {

    private final FakeBotManager bots;
    private final String bridgeUri = BridgeUriResolver.resolve();
    private final ServerBridgeWebSocketClient ws = new ServerBridgeWebSocketClient(bridgeUri);
    private final BotSoakTestController soak;

    private static final java.util.concurrent.atomic.AtomicInteger INSTANCES =
        new java.util.concurrent.atomic.AtomicInteger(0);

    private final int instanceId = INSTANCES.incrementAndGet();

    private boolean spawned = false;
    private boolean DEBUG_WS = false;

    public ServerBotHooks(FakeBotManager bots) {
        this.bots = bots;
        this.soak = new BotSoakTestController(bots, ws);
        com.example.aiagent.BotMod.getInstance().setBridgeClient(this.ws);
        com.example.aiagent.BotMod.getInstance().setSoakController(this.soak);

        MinecraftForge.EVENT_BUS.register(this);

        System.out.println("[AI-BOT] ServerBotHooks registered. instanceId=" + instanceId
                + " this=" + System.identityHashCode(this));
        System.out.println("[AI-BOT][SERVER-WS] Will connect to " + bridgeUri + " (logical server side)");
    }

    private static boolean shouldRun(MinecraftServer server) {
        return server != null;
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
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        int n = sp.getServer() != null ? sp.getServer().getPlayerList().getPlayers().size() : -1;
        System.out.println("[AI-BOT][DEBUG][ServerBotHooks] PlayerLoggedIn name=" + sp.getGameProfile().getName()
                + " playerCount=" + n + " -> bridge setAutoConnect(true) ensureConnected()");
        ws.setAutoConnect(true);
        ws.ensureConnected();
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        String name = player.getGameProfile().getName();
        server.execute(() -> {
            int remaining = server.getPlayerList().getPlayers().size();
            System.out.println("[AI-BOT][DEBUG][ServerBotHooks] PlayerLoggedOut name=" + name
                    + " remainingPlayers=" + remaining);
            if (server.getPlayerList().getPlayers().isEmpty()) {
                System.out.println("[AI-BOT][DEBUG][ServerBotHooks] last player left -> pauseForPlayerMode()");
                ws.pauseForPlayerMode();
            }
        });
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

        soak.tick(level);

        // Send results back
        ws.drainCompletedResultsAndSend(bots);
    }

}
