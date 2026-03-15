package com.example.aiagent.client;

import com.example.aiagent.BotMod;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
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
    private static final String BRIDGE_URI = "ws://127.0.0.1:8765";
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
            ForgeWebSocketClient client = new ForgeWebSocketClient(new URI(BRIDGE_URI));
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
            System.out.println("[AI-BOT] WS connecting -> " + BRIDGE_URI);

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

    // -------------------------------------------------------------------------
    // Auto mode selection on login
    // -------------------------------------------------------------------------
    private void autoSelectControlMode(Minecraft mc) {
        boolean singleplayer = mc.hasSingleplayerServer();

        ForgeWebSocketClient.ControlMode mode =
                singleplayer ? ForgeWebSocketClient.ControlMode.PLAYER
                        : ForgeWebSocketClient.ControlMode.SERVER_BOT;

        ForgeWebSocketClient.setControlMode(mode);

        if (mc.player != null) {
            mc.player.displayClientMessage(
                    Component.literal(singleplayer
                            ? "§a[AI-BOT] Singleplayer: DQN controls LOCAL PLAYER"
                            : "§b[AI-BOT] Multiplayer: DQN controls SERVER FakePlayer"),
                    true
            );
        }

        System.out.println("[AI-BOT] Auto ControlMode -> " + mode + " (singleplayer=" + singleplayer + ")");
    }

    @SubscribeEvent
    public void onClientLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            nextReconnectMs = 0;
            reconnectAttemptIndex = 0;
            ensureBridgeConnected();
            autoSelectControlMode(mc);
        });
    }

    @SubscribeEvent
    public void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event) {
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

        // CTRL+P: toggle AI enabled
        if (TOGGLE_AI_KEY.consumeClick()) {
            aiEnabled = !aiEnabled;
            ForgeWebSocketClient.setAiEnabled(aiEnabled);
            p.displayClientMessage(
                    Component.literal("[AI-BOT] AI " + (aiEnabled ? "ENABLED" : "DISABLED")),
                    true
            );
            System.out.println("[AI-BOT] CTRL+P -> aiEnabled=" + aiEnabled);
        }

        // CTRL+M: manual toggle control mode
        if (TOGGLE_MODE_KEY.consumeClick()) {
            ForgeWebSocketClient.ControlMode mode = ForgeWebSocketClient.getControlMode();
            ForgeWebSocketClient.ControlMode next =
                    (mode == ForgeWebSocketClient.ControlMode.PLAYER)
                            ? ForgeWebSocketClient.ControlMode.SERVER_BOT
                            : ForgeWebSocketClient.ControlMode.PLAYER;

            ForgeWebSocketClient.setControlMode(next);
            p.displayClientMessage(Component.literal("[AI-BOT] Control Mode: " + next), true);
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

        if (!p.isAlive()) {
            try { p.respawn(); } catch (Exception ignored) {}
        }

        BlockPos spawn = level.getSharedSpawnPos();
        p.teleportTo(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);

        p.setHealth(p.getMaxHealth());
        p.getFoodData().setFoodLevel(20);
        p.getFoodData().setSaturation(5.0f);
        p.getInventory().clearContent();

        try {
            var server = mc.getSingleplayerServer();
            if (server != null) {
                ServerLevel overworld = server.overworld();
                overworld.setDayTime(0);
                overworld.setWeatherParameters(6000, 0, false, false);
            }
        } catch (Exception ignored) {}

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

        // --------------------
        // pose
        // --------------------
        JsonObject pose = new JsonObject();
        pose.addProperty("x", p.getX());
        pose.addProperty("y", p.getY());
        pose.addProperty("z", p.getZ());
        pose.addProperty("yaw", p.getYRot());
        pose.addProperty("pitch", p.getXRot());

        // --------------------
        // rays (16 around player)
        // --------------------
        JsonArray rays = new JsonArray();
        int count = 16;
        double fov = 360.0;
        double maxDist = 5.0;

        for (int i = 0; i < count; i++) {
            double angle = (i / (double) count) * fov;
            float yaw = (float) (p.getYRot() + angle);

            Vec3 from = p.getEyePosition(1f);
            Vec3 dir = Vec3.directionFromRotation(p.getXRot(), yaw);
            Vec3 to = from.add(dir.scale(maxDist));

            var hit = level.clip(new net.minecraft.world.level.ClipContext(
                    from, to,
                    net.minecraft.world.level.ClipContext.Block.COLLIDER,
                    net.minecraft.world.level.ClipContext.Fluid.NONE,
                    p));

            JsonObject r = new JsonObject();
            boolean hitBlock = hit.getType() != net.minecraft.world.phys.HitResult.Type.MISS;
            r.addProperty("hit", hitBlock);
            r.addProperty("dist", hitBlock ? from.distanceTo(hit.getLocation()) : maxDist);
            r.addProperty("angle_deg", angle);
            rays.add(r);
        }

        // --------------------
        // inventory
        // --------------------
        JsonArray hotbar = new JsonArray();
        var inv = p.getInventory();

        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getItem(i);

            JsonObject item = new JsonObject();
            String id = "minecraft:air";
            try {
                id = net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(s.getItem())
                        .toString();
            } catch (Exception ignored) {}

            item.addProperty("id", id);
            item.addProperty("count", s.getCount());
            hotbar.add(item);
        }

        JsonObject inventory = new JsonObject();
        inventory.addProperty("selected_slot", inv.selected);
        inventory.add("hotbar", hotbar);

        // --------------------
        // front_clear
        // --------------------
        boolean frontClear = true;
        try {
            if (rays.size() > 0) {
                JsonObject r0 = rays.get(0).getAsJsonObject();
                boolean hitBlock = r0.get("hit").getAsBoolean();
                double dist = r0.get("dist").getAsDouble();
                frontClear = !(hitBlock && dist < 1.25);
            }
        } catch (Exception ignored) {}

        // --------------------
        // world
        // --------------------
        JsonObject world = new JsonObject();
        long dayTime = level.getDayTime() % 24000L;
        world.addProperty("time_of_day", (double) dayTime);

        String weather = "clear";
        if (level.isThundering()) weather = "thunder";
        else if (level.isRaining()) weather = "rain";
        world.addProperty("weather", weather);

        String biomeName = "unknown";
        try {
            var biomeKey = level.getBiome(p.blockPosition()).unwrapKey();
            if (biomeKey.isPresent())
                biomeName = biomeKey.get().location().toString();
        } catch (Exception ignored) {}
        world.addProperty("biome", biomeName);

        // --------------------
        // collision
        // --------------------
        JsonObject collision = new JsonObject();
        collision.addProperty("is_grounded", p.onGround());
        collision.addProperty("is_colliding", p.horizontalCollision || p.verticalCollision);

        double vx = p.getDeltaMovement().x;
        double vz = p.getDeltaMovement().z;
        boolean noProgress = (vx * vx + vz * vz) < 0.0004;
        collision.addProperty("no_progress", noProgress);

        // --------------------
        // entities (empty for now)
        // --------------------
        JsonArray entities = new JsonArray();

        // --------------------
        // final payload
        // --------------------
        JsonObject payload = new JsonObject();
        payload.add("pose", pose);
        payload.add("rays", rays);
        payload.addProperty("front_clear", frontClear);
        payload.add("world", world);
        payload.add("inventory", inventory);
        payload.add("collision", collision);
        payload.add("entities", entities);

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
