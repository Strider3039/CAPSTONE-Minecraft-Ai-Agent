package com.example.aiagent.client;

import com.example.aiagent.BotMod;
import com.example.aiagent.BridgeUriResolver;
import com.example.aiagent.common.AgentModeSharedLogic;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.client.settings.KeyModifier;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CLIENT ONLY:
 * - WebSocket bridge connection
 * - Observations
 * - Episode logic
 * - Client commands
 *
 * Controls:
 *  - CTRL+P : toggle AI enabled/disabled
 *  - CTRL+M : manual toggle control mode PLAYER <-> SERVER_BOT
 *
 * Auto behavior:
 *  - Singleplayer: ControlMode.PLAYER (DQN controls local player)
 *  - Multiplayer:  ControlMode.SERVER_BOT (DQN controls FakePlayer via packet forward)
 */
public class ClientBridgeHooks {

    // One singleton instance (avoid double-registration / confusing state)
    private static ClientBridgeHooks INSTANCE;

    public static void init() {
        if (INSTANCE != null) return;
        INSTANCE = new ClientBridgeHooks();
        MinecraftForge.EVENT_BUS.register(INSTANCE);
        System.out.println("[AI-BOT] ClientBridgeHooks initialized (singleton).");
    }

    // ----------------------------
    // WebSocket / Bridge
    // ----------------------------
    private volatile ForgeWebSocketClient wsClient;
    private long reconnectCount = 0;
    private long droppedCount = 0;

    // Reconnect with backoff (aligned with default.yaml: 250, 1000, 2000, 5000 ms + jitter)
    private static final long[] BACKOFF_MS = { 500, 1000, 2000, 5000 };
    private static final long JITTER_MS = 150;
    private volatile long nextReconnectMs = 0;
    private volatile int reconnectAttemptIndex = 0;
    private volatile boolean connecting = false;

    // ----------------------------
    // Telemetry
    // ----------------------------
    private final ConcurrentHashMap<Long, Long> latencyMap = new ConcurrentHashMap<>();

    // ----------------------------
    // Keybinds
    // ----------------------------
    public static final KeyMapping TOGGLE_AI_KEY =
            new KeyMapping(
                    "key.aibot.toggle_ai",
                    KeyConflictContext.IN_GAME,
                    KeyModifier.CONTROL,
                    InputConstants.Type.KEYSYM,
                    GLFW.GLFW_KEY_P,
                    "key.categories.misc"
            );

    public static final KeyMapping TOGGLE_MODE_KEY =
            new KeyMapping(
                    "key.aibot.toggle_mode",
                    KeyConflictContext.IN_GAME,
                    KeyModifier.CONTROL,
                    InputConstants.Type.KEYSYM,
                    GLFW.GLFW_KEY_M,
                    "key.categories.misc"
            );

    @Mod.EventBusSubscriber(modid = BotMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static class ModBusClient {
        @SubscribeEvent
        public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
            event.register(TOGGLE_AI_KEY);
            event.register(TOGGLE_MODE_KEY);
            System.out.println("[AI-BOT] Registered keybinds: CTRL+P (AI), CTRL+M (mode)");
        }
    }

    // ----------------------------
    // Local state
    // ----------------------------
    private boolean aiEnabled = true;
    private long lastSendMs = 0;

    // Episode tracking
    private boolean episodeActive = false;
    private long episodeStartTick = -1L;
    private static final long EPISODE_TICKS = 24000L;

    // Respawn/death detection helpers
    private UUID lastPlayerId = null;
    private int lastAir = -1;
    private boolean wasAlive = true;

    /** True when the client was in a remote dedicated world (not integrated SP) last in-game tick. */
    private volatile boolean lastTickPlayingRemoteDedicated = false;

    /** Last printed ensureBridgeConnected summary (avoid spam). */
    private String lastEnsureBridgeDebugSummary = "";

    // -------------------------------------------------------------------------
    // Bridge connection and reconnect with backoff
    // -------------------------------------------------------------------------

    /** Called on game thread when the WebSocket closes. Schedules next reconnect. */
    private void onBridgeDisconnected() {
        wsClient = null;
        long delay = getBackoffWithJitter();
        nextReconnectMs = System.currentTimeMillis() + delay;
        if (reconnectAttemptIndex < BACKOFF_MS.length - 1) {
            reconnectAttemptIndex++;
        }
        System.out.println("[AI-BOT] Bridge disconnected; will retry in " + delay + " ms (attempt " + reconnectAttemptIndex + ")");
    }

    private long getBackoffWithJitter() {
        long base = BACKOFF_MS[Math.min(reconnectAttemptIndex, BACKOFF_MS.length - 1)];
        long jitter = (long) ((Math.random() * 2 - 1) * JITTER_MS);
        return Math.max(100, base + jitter);
    }

    /**
     * Ensures we have an open connection to the bridge. Call on login and every client tick.
     * If disconnected, retries with backoff so the demo recovers after a bridge restart.
     *
     * In SERVER_BOT + multiplayer: do not connect — only the dedicated server connects to the bridge;
     * the client receives bot state via S2C packets from the server.
     */
    private void ensureBridgeConnected() {
        Minecraft mc = Minecraft.getInstance();
        boolean multiplayer = mc != null && mc.getConnection() != null;
        boolean serverBotMode = ForgeWebSocketClient.getControlMode() == ForgeWebSocketClient.ControlMode.SERVER_BOT;
        boolean wsOpen = wsClient != null && wsClient.isOpen();

        String summary = "mp=" + multiplayer + "|serverBot=" + serverBotMode + "|wsOpen=" + wsOpen + "|connecting=" + connecting;
        if (!summary.equals(lastEnsureBridgeDebugSummary)) {
            lastEnsureBridgeDebugSummary = summary;
            System.out.println("[AI-BOT][DEBUG][ClientBridge] ensureBridgeConnected: " + summary
                    + " -> " + (serverBotMode && multiplayer ? "SKIP client WS (dedicated server owns bridge)" : "may open client WS"));
        }

        if (serverBotMode && multiplayer) {
            // Dedicated MP + SERVER_BOT: server holds the bridge connection; client must not connect.
            if (wsClient != null) {
                try { wsClient.closeBlocking(); } catch (Exception ignored) {}
                wsClient = null;
            }
            connecting = false;
            return;
        }

        if (wsClient != null && wsClient.isOpen()) return;
        if (connecting) return;
        long now = System.currentTimeMillis();
        if (wsClient != null) {
            wsClient = null;
        }
        if (now < nextReconnectMs) return;

        connecting = true;
        try {
            String bridgeUri = BridgeUriResolver.resolve();
            ForgeWebSocketClient client = new ForgeWebSocketClient(new URI(bridgeUri));
            client.setOnReconnect(() -> {
                reconnectCount++;
                System.out.println("[AI-BOT] Reconnected (" + reconnectCount + ")");
                ForgeWebSocketClient.setAiEnabled(aiEnabled);
                connecting = false;
            });
            client.setOnDisconnect(() -> {
                if (mc != null) {
                    mc.execute(this::onBridgeDisconnected);
                } else {
                    onBridgeDisconnected();
                }
            });

            wsClient = client;
            ForgeWebSocketClient.setAiEnabled(aiEnabled);
            System.out.println("[AI-BOT] WS connecting -> " + bridgeUri);

            ForgeWebSocketClient finalClient = client;
            new Thread(() -> {
                try {
                    finalClient.connectBlocking();
                    if (!finalClient.isOpen()) {
                        wsClient = null;
                        connecting = false;
                    }
                } catch (Exception e) {
                    System.err.println("[AI-BOT] WS connectBlocking failed: " + e.getMessage());
                    wsClient = null;
                    connecting = false;
                }
            }, "WS-Connect").start();
        } catch (Exception e) {
            wsClient = null;
            connecting = false;
            System.err.println("[AI-BOT] WS setup failed: " + e.getMessage());
        }
    }

    @SubscribeEvent
    public void onClientLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            boolean mp = mc.getConnection() != null;
            boolean spIntegrated = mc.getSingleplayerServer() != null;
            System.out.println("[AI-BOT][DEBUG][ClientBridge] LoggingIn: ControlMode=" + ForgeWebSocketClient.getControlMode()
                    + " multiplayer=" + mp + " integratedServer=" + spIntegrated);
            lastEnsureBridgeDebugSummary = "";
            nextReconnectMs = 0;
            reconnectAttemptIndex = 0;
            ensureBridgeConnected();
        });
    }

    @SubscribeEvent
    public void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        if (lastTickPlayingRemoteDedicated) {
            System.out.println("[AI-BOT][DEBUG][ClientBridge] LoggingOut from remote dedicated; forcing ControlMode PLAYER");
            ForgeWebSocketClient.setControlMode(ForgeWebSocketClient.ControlMode.PLAYER);
            System.out.println("[AI-BOT] Left remote dedicated server; local ControlMode -> PLAYER (client bridge can reconnect in SP).");
        }
        lastTickPlayingRemoteDedicated = false;
        lastEnsureBridgeDebugSummary = "";

        ForgeWebSocketClient c = wsClient;
        wsClient = null;
        nextReconnectMs = 0;
        reconnectAttemptIndex = 0;

        if (c != null) {
            new Thread(() -> {
                try { c.closeBlocking(); } catch (Exception ignored) {}
            }, "WS-Close").start();
        }
    }

    // -------------------------------------------------------------------------
    // MAIN TICK LOOP (reconnect check + observations)
    // -------------------------------------------------------------------------
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.START) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        var p = mc.player;

        lastTickPlayingRemoteDedicated = mc.getConnection() != null && mc.getSingleplayerServer() == null;

        ensureBridgeConnected();

        // respawn detect
        if (lastPlayerId != null && !p.getUUID().equals(lastPlayerId)) {
            System.out.println("[AI-BOT] Respawn detected via UUID change.");
            sendEpisodeEnd("death");
        }
        lastPlayerId = p.getUUID();

        // death detect
        if (p.getHealth() <= 0.1f && wasAlive) {
            System.out.println("[AI-BOT] Health dropped to zero → death.");
            sendEpisodeEnd("death");
        }
        wasAlive = p.getHealth() > 0.1f;

        // drowning/air reset
        if (lastAir > 0 && p.getAirSupply() == p.getMaxAirSupply() && p.getAirSupply() != lastAir) {
            System.out.println("[AI-BOT] Respawn detected via air reset (drowning).");
            sendEpisodeEnd("death");
        }
        lastAir = p.getAirSupply();

        // episode timeout
        if (episodeActive) {
            long nowTicks = mc.level.getGameTime();
            if (episodeStartTick >= 0 && nowTicks - episodeStartTick >= EPISODE_TICKS) {
                System.out.println("[AI-BOT] Episode timeout (1 MC day)");
                sendEpisodeEnd("timeout");
            }
        }

        // CTRL+P: toggle AI enabled (on-screen prompt in all modes)
        if (TOGGLE_AI_KEY.consumeClick()) {
            aiEnabled = !aiEnabled;
            ForgeWebSocketClient.setAiEnabled(aiEnabled);
            p.displayClientMessage(
                    Component.literal((aiEnabled ? "§a" : "§c") + "[AI-BOT] AI switched: " + (aiEnabled ? "ENABLED" : "DISABLED")),
                    true
            );
            System.out.println("[AI-BOT] CTRL+P -> aiEnabled=" + aiEnabled);
        }

        // CTRL+M: manual toggle control mode (on-screen prompt, persists across world joins)
        if (TOGGLE_MODE_KEY.consumeClick()) {
            ForgeWebSocketClient.ControlMode mode = ForgeWebSocketClient.getControlMode();
            ForgeWebSocketClient.ControlMode next =
                    (mode == ForgeWebSocketClient.ControlMode.PLAYER)
                            ? ForgeWebSocketClient.ControlMode.SERVER_BOT
                            : ForgeWebSocketClient.ControlMode.PLAYER;

            ForgeWebSocketClient.setControlMode(next);
            String msg = next == ForgeWebSocketClient.ControlMode.PLAYER
                    ? "§a[AI-BOT] Control mode switched to PLAYER"
                    : "§b[AI-BOT] Control mode switched to SERVER_BOT";
            p.displayClientMessage(Component.literal(msg), true);
            System.out.println("[AI-BOT] CTRL+M -> ControlMode=" + next);
        }

        // send observations
        if (aiEnabled && wsClient != null && wsClient.isOpen()) {
            long now = System.currentTimeMillis();
            if (now - lastSendMs >= 100) {
                sendObservation(mc);
                lastSendMs = now;
            }
        }
    }

    private void sendEpisodeEnd(String reason) {
        if (wsClient != null && wsClient.isOpen()) {
            wsClient.emitEpisodeEnd(reason);
        }
        episodeActive = false;
    }

    // Called by ForgeWebSocketClient on "episode_start"
    public void startNewEpisode(Minecraft mc) {
        var p = mc.player;
        var level = mc.level;
        if (p == null || level == null) return;

        EpisodeController.startNewEpisode(mc);

        episodeStartTick = level.getGameTime();
        episodeActive = true;

        System.out.println("[AI-BOT] Episode started at world spawn.");
    }

    private void sendObservation(Minecraft mc) {

        // Only send client observations in singleplayer / integrated server.
        if (!mc.hasSingleplayerServer()) return;

        var p = mc.player;
        var level = mc.level;
        if (p == null || level == null) return;
        List<AgentModeSharedLogic.RaySample> rays = buildObservationRays(p, level);
        List<AgentModeSharedLogic.HotbarSlot> hotbar = buildHotbarSummary(p);
        List<AgentModeSharedLogic.NearbyEntitySample> entities = buildNearbyEntities(p, level);

        JsonObject payload = AgentModeSharedLogic.buildObservationPayload(new AgentModeSharedLogic.ObservationAdapter() {
            @Override public double x() { return p.getX(); }
            @Override public double y() { return p.getY(); }
            @Override public double z() { return p.getZ(); }
            @Override public float yaw() { return p.getYRot(); }
            @Override public float pitch() { return p.getXRot(); }
            @Override public List<AgentModeSharedLogic.RaySample> rays() { return rays; }
            @Override public double timeOfDay() { return (double) (level.getDayTime() % 24000L); }
            @Override public String weather() {
                if (level.isThundering()) return "thunder";
                if (level.isRaining()) return "rain";
                return "clear";
            }
            @Override public String biome() {
                try {
                    return level.getBiome(p.blockPosition()).unwrapKey().map(k -> k.location().toString()).orElse("unknown");
                } catch (Exception ignored) {
                    return "unknown";
                }
            }
            @Override public int selectedSlot() { return p.getInventory().selected; }
            @Override public List<AgentModeSharedLogic.HotbarSlot> hotbar() { return hotbar; }
            @Override public boolean isGrounded() { return p.onGround(); }
            @Override public boolean isColliding() { return p.horizontalCollision || p.verticalCollision; }
            @Override public boolean noProgress() {
                double vx = p.getDeltaMovement().x;
                double vz = p.getDeltaMovement().z;
                return (vx * vx + vz * vz) < 0.0004;
            }
            @Override public List<AgentModeSharedLogic.NearbyEntitySample> nearbyEntities() { return entities; }
        });

        JsonObject obs = new JsonObject();
        obs.addProperty("proto", "1");
        obs.addProperty("kind", "observation");
        long seq = level.getGameTime();
        obs.addProperty("seq", seq);
        obs.addProperty("timestamp", System.currentTimeMillis() / 1000.0);
        obs.add("payload", payload);

        try {
            if (wsClient != null && wsClient.isOpen()) {
                wsClient.send(BotMod.GSON.toJson(obs));
                latencyMap.put(seq, System.currentTimeMillis());
            } else {
                droppedCount++;
            }
        } catch (Exception e) {
            droppedCount++;
            System.err.println("[AI-BOT] Send failed: " + e.getMessage());
        }
    }

    private static List<AgentModeSharedLogic.RaySample> buildObservationRays(
            net.minecraft.world.entity.player.Player p,
            net.minecraft.world.level.Level level
    ) {
        List<AgentModeSharedLogic.RaySample> rays = new ArrayList<>();
        int count = 16;
        double maxDist = 5.0;

        for (int i = 0; i < count; i++) {
            double angle = (i / (double) count) * 360.0;
            float yaw = (float) (p.getYRot() + angle);

            Vec3 from = p.getEyePosition();
            Vec3 dir = Vec3.directionFromRotation(p.getXRot(), yaw);
            Vec3 to = from.add(dir.scale(maxDist));

            var hit = level.clip(new net.minecraft.world.level.ClipContext(
                    from, to,
                    net.minecraft.world.level.ClipContext.Block.COLLIDER,
                    net.minecraft.world.level.ClipContext.Fluid.NONE,
                    p));

            boolean hitBlock = hit.getType() != net.minecraft.world.phys.HitResult.Type.MISS;
            rays.add(new AgentModeSharedLogic.RaySample(
                    hitBlock,
                    hitBlock ? from.distanceTo(hit.getLocation()) : maxDist,
                    angle
            ));
        }

        return rays;
    }

    private static List<AgentModeSharedLogic.HotbarSlot> buildHotbarSummary(net.minecraft.world.entity.player.Player p) {
        List<AgentModeSharedLogic.HotbarSlot> hotbar = new ArrayList<>();
        var inv = p.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getItem(i);
            String id = "minecraft:air";
            try {
                id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
            } catch (Exception ignored) {
            }
            hotbar.add(new AgentModeSharedLogic.HotbarSlot(id, s.getCount()));
        }
        return hotbar;
    }

    private static List<AgentModeSharedLogic.NearbyEntitySample> buildNearbyEntities(
            net.minecraft.world.entity.player.Player p,
            net.minecraft.world.level.Level level
    ) {
        List<AgentModeSharedLogic.NearbyEntitySample> entities = new ArrayList<>();
        AABB box = p.getBoundingBox().inflate(8.0, 4.0, 8.0);
        List<Entity> nearby = level.getEntities(p, box, e -> e != null && e.isAlive() && !e.isRemoved());
        nearby.sort(Comparator.comparingDouble(e -> e.distanceToSqr(p)));

        int cap = Math.min(8, nearby.size());
        for (int i = 0; i < cap; i++) {
            Entity e = nearby.get(i);
            String typeId = "unknown";
            try {
                typeId = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
            } catch (Exception ignored) {
            }
            entities.add(new AgentModeSharedLogic.NearbyEntitySample(
                    e.getId(),
                    typeId,
                    Math.sqrt(e.distanceToSqr(p)),
                    p.hasLineOfSight(e)
            ));
        }
        return entities;
    }


    @SubscribeEvent
    public void onRegisterCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("aibot_mode").executes(ctx -> {
                    var mode = ForgeWebSocketClient.getControlMode();
                    ctx.getSource().sendSystemMessage(Component.literal("[AI-BOT] ControlMode = " + mode));
                    return 1;
                })
        );
    }
}
