package com.example.aiagent;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.net.URI;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.item.ItemStack;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

// NEW imports for episode logic
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

@Mod(BotMod.MODID)
public class BotMod {

    public static final String MODID = "ai_agent_bot";
    private static BotMod INSTANCE;
    public static BotMod getInstance() { return INSTANCE; }

    public static final Gson GSON = new GsonBuilder().create();

    ForgeWebSocketClient wsClient;
    private boolean triedConnect = false;

    private final ConcurrentHashMap<Long, Long> latencyMap = new ConcurrentHashMap<>();

    private static final KeyMapping TOGGLE_KEY =
            new KeyMapping(
                    "key.aibot.toggle",
                    InputConstants.Type.KEYSYM,
                    GLFW.GLFW_KEY_P,
                    "key.categories.misc"
            );

    private boolean aiEnabled = true;
    private long lastSendMs = 0;
    private long reconnectCount = 0;
    private long droppedCount = 0;

    // Episode tracking
    private boolean episodeActive = false;
    private long episodeStartTick = -1L;
    private static final long EPISODE_TICKS = 24000L;

    // Fallback death detection
    private boolean lastAliveState = true;

    // Respawn detection (NEW)
    private UUID lastPlayerId = null;
    private int lastAir = -1;
    private boolean wasAlive = true;

    @Mod.EventBusSubscriber(
            modid = BotMod.MODID,
            value = Dist.CLIENT,
            bus = Mod.EventBusSubscriber.Bus.MOD
    )
    public static class ModBusClient {
        @SubscribeEvent
        public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
            event.register(TOGGLE_KEY);
        }
    }

    public BotMod() {
        MinecraftForge.EVENT_BUS.register(this);
        INSTANCE = this;
    }

    // -------------------------------------------------------------------------
    // MAIN TICK LOOP (observations + reliable death & respawn detection)
    // -------------------------------------------------------------------------
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.START) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        var p = mc.player;

        // ------------------------------------------
        // Establish WS connection once
        // ------------------------------------------
        if (!triedConnect) {
            triedConnect = true;
            try {
                wsClient = new ForgeWebSocketClient(new URI("ws://127.0.0.1:8765"));
                wsClient.connect();
                wsClient.setOnReconnect(() -> {
                    reconnectCount++;
                    System.out.println("[AI-BOT] Reconnected (" + reconnectCount + ")");
                });
                System.out.println("[AI-BOT] Connecting to AI bridge…");
            } catch (Exception e) {
                System.err.println("[AI-BOT] WebSocket connect failed:");
                e.printStackTrace();
            }
        }

        // ============================================================
        // 1️⃣ Detect respawn (UUID change ALWAYS happens on respawn)
        // ============================================================
        if (lastPlayerId != null && !p.getUUID().equals(lastPlayerId)) {
            System.out.println("[AI-BOT] Respawn detected via UUID change.");
            sendEpisodeEnd("death");
        }
        lastPlayerId = p.getUUID();

        // ============================================================
        // 2️⃣ Detect death when health briefly hits <= 0
        // ============================================================
        if (p.getHealth() <= 0.1f && wasAlive) {
            System.out.println("[AI-BOT] Health dropped to zero → death.");
            sendEpisodeEnd("death");
        }
        wasAlive = p.getHealth() > 0.1f;

        // ============================================================
        // 3️⃣ Detect drowning/respawn via AIR reset
        // ============================================================
        if (lastAir > 0 && p.getAirSupply() == p.getMaxAirSupply() && p.getAirSupply() != lastAir) {
            System.out.println("[AI-BOT] Respawn detected via air reset (drowning).");
            sendEpisodeEnd("death");
        }
        lastAir = p.getAirSupply();

        // ============================================================
        // 4️⃣ Episode timeout (full MC day)
        // ============================================================
        if (episodeActive && mc.level != null) {
            long nowTicks = mc.level.getGameTime();
            if (episodeStartTick >= 0 && nowTicks - episodeStartTick >= EPISODE_TICKS) {
                System.out.println("[AI-BOT] Episode timeout (1 MC day)");
                sendEpisodeEnd("timeout");
            }
        }

        // ------------------------------------------
        // AI toggle key
        // ------------------------------------------
        if (TOGGLE_KEY.consumeClick()) {
            aiEnabled = !aiEnabled;
            ForgeWebSocketClient.setAiEnabled(aiEnabled);
            p.displayClientMessage(
                    Component.literal("[AI-BOT] AI " + (aiEnabled ? "ENABLED" : "DISABLED")),
                    true
            );
        }

        // ------------------------------------------
        // Send observations
        // ------------------------------------------
        if (aiEnabled && wsClient != null && wsClient.isOpen()) {
            long now = System.currentTimeMillis();
            if (now - lastSendMs >= 100) {
                sendObservation(mc);
                lastSendMs = now;
            }
        }
    }

    // -------------------------------------------------------------------------
    // OFFICIAL DEATH EVENT (only fires when doImmediateRespawn = false)
    // -------------------------------------------------------------------------
    @SubscribeEvent
    public void onPlayerDeath(LivingDeathEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null) return;
        if (event.getEntity() != mc.player) return;

        System.out.println("[AI-BOT] Official DeathEvent fired.");
        sendEpisodeEnd("death");
    }

    // -------------------------------------------------------------------------
    // Helper → Safely send episode_end
    // -------------------------------------------------------------------------
    private void sendEpisodeEnd(String reason) {
        if (wsClient != null && wsClient.isOpen()) {
            wsClient.emitEpisodeEnd(reason);
        }
        episodeActive = false;
    }

    // -------------------------------------------------------------------------
    // Called when Python sends "episode_start"
    // -------------------------------------------------------------------------
    public void startNewEpisode(Minecraft mc) {
        var p = mc.player;
        var level = mc.level;
        if (p == null || level == null) return;

        // Auto-respawn
        if (!p.isAlive()) {
            try { p.respawn(); } catch (Exception ignored) {}
        }

        // Teleport to spawn
        BlockPos spawn = level.getSharedSpawnPos();
        p.teleportTo(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);

        // Reset stats
        p.setHealth(p.getMaxHealth());
        p.getFoodData().setFoodLevel(20);
        p.getFoodData().setSaturation(5.0f);
        p.getInventory().clearContent();

        // Reset world time/weather (singleplayer)
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
        lastAliveState = true;

        System.out.println("[AI-BOT] Episode started at world spawn.");
    }

    // -------------------------------------------------------------------------
    // OBSERVATIONS
    // -------------------------------------------------------------------------
    private void sendObservation(Minecraft mc) {
        var p = mc.player;
        var level = mc.level;
        if (p == null || level == null) return;

        JsonObject pose = new JsonObject();
        pose.addProperty("x", p.getX());
        pose.addProperty("y", p.getY());
        pose.addProperty("z", p.getZ());
        pose.addProperty("yaw", p.getYRot());
        pose.addProperty("pitch", p.getXRot());

        JsonArray rays = new JsonArray();
        int count = 16;
        double fov = 360.0;
        double maxDist = 5.0;

        for (int i = 0; i < count; i++) {
            double angle = (i / (double) count) * fov;
            float yaw = (float) (p.getYRot() + angle);
            var from = p.getEyePosition(1f);
            Vec3 dir = Vec3.directionFromRotation(p.getXRot(), yaw);
            var to = from.add(dir.scale(maxDist));

            var hit = level.clip(new net.minecraft.world.level.ClipContext(
                    from, to,
                    net.minecraft.world.level.ClipContext.Block.COLLIDER,
                    net.minecraft.world.level.ClipContext.Fluid.NONE,
                    p));

            JsonObject r = new JsonObject();
            boolean hitBlock = hit.getType() != net.minecraft.world.phys.HitResult.Type.MISS;
            r.addProperty("hit", hitBlock);
            r.addProperty("dist", hitBlock ? from.distanceTo(hit.getLocation()) : maxDist);
            r.addProperty("angle_deg", (i / (double) count) * fov);
            rays.add(r);
        }

        boolean frontClear = rays.size() == 0
                || rays.get(0).getAsJsonObject().get("dist").getAsDouble() >= 1.2;

        JsonArray entities = new JsonArray();
        int eCount = 0;
        for (var e : level.getEntities(p, p.getBoundingBox().inflate(8))) {
            if (eCount++ >= 8) break;
            JsonObject ent = new JsonObject();
            ent.addProperty("id", e.getId());
            ent.addProperty("type", e.getType().toShortString());
            ent.addProperty("dist", e.distanceTo(p));
            ent.addProperty("los", true);
            entities.add(ent);
        }

        JsonObject world = new JsonObject();
        world.addProperty("time_of_day", level.getDayTime());
        world.addProperty("weather", level.isThundering() ? "thunder" :
                (level.isRaining() ? "rain" : "clear"));
        world.addProperty("biome",
                level.getBiome(p.blockPosition()).unwrapKey().get().location().toString());

        JsonArray hotbar = new JsonArray();
        var inv = p.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getItem(i);
            JsonObject item = new JsonObject();
            item.addProperty("id", s.getItem().toString());
            item.addProperty("count", s.getCount());
            hotbar.add(item);
        }

        int selectedIdx = 0;
        ItemStack held = p.getMainHandItem();
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getItem(i);
            if (ItemStack.isSameItemSameComponents(held, s)) {
                selectedIdx = i;
                break;
            }
        }

        JsonObject inventory = new JsonObject();
        inventory.addProperty("selected_slot", selectedIdx);
        inventory.add("hotbar", hotbar);

        JsonObject collision = new JsonObject();
        collision.addProperty("is_grounded", p.onGround());
        collision.addProperty("is_colliding", p.horizontalCollision);
        collision.addProperty("no_progress", false);

        JsonObject payload = new JsonObject();
        payload.add("pose", pose);
        payload.add("rays", rays);
        payload.addProperty("front_clear", frontClear);
        payload.add("entities", entities);
        payload.add("world", world);
        payload.add("inventory", inventory);
        payload.add("collision", collision);

        JsonObject obs = new JsonObject();
        obs.addProperty("proto", "1");
        obs.addProperty("kind", "observation");

        long seq = level.getGameTime();
        obs.addProperty("seq", seq);
        obs.addProperty("timestamp", System.currentTimeMillis() / 1000.0);
        obs.add("payload", payload);

        try {
            if (wsClient != null && wsClient.isOpen()) {
                wsClient.send(GSON.toJson(obs));
                latencyMap.put(seq, System.currentTimeMillis());
            } else {
                droppedCount++;
            }
        } catch (Exception e) {
            droppedCount++;
            System.err.println("[AI-BOT] Send failed: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Commands
    // -------------------------------------------------------------------------
    @SubscribeEvent
    public void onRegisterCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("aibot").executes(ctx -> {
                    ctx.getSource().sendSystemMessage(Component.literal("AI bot is alive!"));
                    return 1;
                })
        );

        event.getDispatcher().register(
                Commands.literal("reset_episode").executes(ctx -> {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc != null && mc.player != null &&
                            wsClient != null && wsClient.isOpen()) {

                        wsClient.emitEpisodeEnd("manual_reset");
                        ctx.getSource().sendSystemMessage(
                                Component.literal("[AI-BOT] Requested episode reset."));
                    }
                    return 1;
                })
        );
    }
}
