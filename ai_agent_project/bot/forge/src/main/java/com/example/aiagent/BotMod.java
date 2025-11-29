package com.example.aiagent;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.net.URI;
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

    // add this nested class anywhere inside BotMod (top-level is fine too)
    @net.minecraftforge.fml.common.Mod.EventBusSubscriber(
        modid = BotMod.MODID,
        value = Dist.CLIENT,
        bus = net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus.MOD
    )
    public static class ModBusClient {
        @SubscribeEvent
        public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
            event.register(TOGGLE_KEY);
        }
    }

    public static final String MODID = "ai_agent_bot";
    private static BotMod INSTANCE;
    public static BotMod getInstance() { return INSTANCE; }

    public static final Gson GSON = new GsonBuilder().create();
    ForgeWebSocketClient wsClient;
    private boolean triedConnect = false;

    private final ConcurrentHashMap<Long, Long> latencyMap = new ConcurrentHashMap<>();

    private static final KeyMapping TOGGLE_KEY =
        new KeyMapping("key.aibot.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_P, "key.categories.misc");

    private boolean aiEnabled = true;
    private long lastSendMs = 0;
    private static final long MIN_SEND_INTERVAL_MS = 70; // ~14 Hz
    private long reconnectCount = 0;
    private long droppedCount = 0;

    // Episode tracking
    private boolean episodeActive = false;
    private long episodeStartTick = -1L;
    private static final long EPISODE_TICKS = 24000L; // one Minecraft day

    public BotMod() {
        MinecraftForge.EVENT_BUS.register(this);
        INSTANCE = this;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.START) return; // skip start phase
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        // one-time connect
        if (!triedConnect) {
            triedConnect = true;
            try {
                wsClient = new ForgeWebSocketClient(new URI("ws://127.0.0.1:8765"));
                wsClient.connect();
                wsClient.setOnReconnect(() -> {
                    reconnectCount++;
                    System.out.println("[AI-BOT] Reconnected (" + reconnectCount + ")");
                    // optional: sendObservation(mc);
                });
                System.out.println("[AI-BOT] Connecting to AI bridge…");
            } catch (Exception e) {
                System.err.println("[AI-BOT] Failed to connect:");
                e.printStackTrace();
            }
        }

        // Episode timeout logic (one full Minecraft day per episode)
        if (episodeActive && mc.level != null) {
            long now = mc.level.getGameTime();
            if (episodeStartTick >= 0 && now - episodeStartTick >= EPISODE_TICKS) {
                System.out.println("[AI-BOT] Episode timeout reached (full Minecraft day).");
                if (wsClient != null && wsClient.isOpen()) {
                    wsClient.emitEpisodeEnd("timeout");
                }
                episodeActive = false;
            }
        }

        // toggle enable/disable
        if (TOGGLE_KEY.consumeClick()) {
            aiEnabled = !aiEnabled;
            com.example.aiagent.ForgeWebSocketClient.setAiEnabled(
                !com.example.aiagent.ForgeWebSocketClient.isAiEnabled()
            );
            mc.player.displayClientMessage(
                Component.literal("[AI-BOT] AI " + (aiEnabled ? "ENABLED" : "DISABLED")), true);
        }

        if (aiEnabled && wsClient != null && wsClient.isOpen()) {
            long now = System.currentTimeMillis();
            if (now - lastSendMs >= 100) { // 10 Hz
                sendObservation(mc);
                lastSendMs = now;
            }
        }
    }

    // Player death → end episode(reason="death"), no auto-teleport here.
    @SubscribeEvent
    public void onPlayerDeath(LivingDeathEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null) return;
        if (event.getEntity() != mc.player) return;

        System.out.println("[AI-BOT] Player died, ending episode.");
        if (wsClient != null && wsClient.isOpen()) {
            wsClient.emitEpisodeEnd("death");
        }
        episodeActive = false;
    }

    // Start a new episode: teleport, reset health/hunger, clear inventory, time & weather, emit EPISODE_START
    private void startNewEpisode(Minecraft mc) {
        var p = mc.player;
        var level = mc.level;
        if (p == null || level == null) return;

        // If the player is on death screen, try to respawn
        if (!p.isAlive()) {
            try {
                p.respawn(); // method name valid on modern LocalPlayer; adjust if needed
            } catch (Exception e) {
                System.err.println("[AI-BOT] Failed to auto-respawn: " + e.getMessage());
            }
        }

        // Teleport to world spawn (default world spawn, not bed)
        BlockPos spawn = level.getSharedSpawnPos();
        double sx = spawn.getX() + 0.5;
        double sy = spawn.getY();
        double sz = spawn.getZ() + 0.5;
        p.teleportTo(sx, sy, sz);

        // Reset health & hunger
        p.setHealth(p.getMaxHealth());
        p.getFoodData().setFoodLevel(20);
        p.getFoodData().setSaturation(5.0f);

        // Clear inventory (Option A)
        p.getInventory().clearContent();

        // Reset time & weather on server if in singleplayer
        try {
            var server = mc.getSingleplayerServer();
            if (server != null) {
                ServerLevel overworld = server.overworld();
                // set day time to 0 (start of day)
                overworld.setDayTime(0);
                // clear weather
                overworld.setWeatherParameters(6000, 0, false, false);
            }
        } catch (Exception e) {
            System.err.println("[AI-BOT] Failed to reset time/weather: " + e.getMessage());
        }

        // Mark episode start tick
        episodeStartTick = level.getGameTime();
        episodeActive = true;

        // Emit EPISODE_START to Python
        if (wsClient != null && wsClient.isOpen()) {
            wsClient.emitEpisodeStart();
            System.out.println("[AI-BOT] Episode started at world spawn.");
        }
    }

    private void sendObservation(Minecraft mc) {
        var p = mc.player;
        var level = mc.level;
        if (p == null || level == null) return;

        // pose
        JsonObject pose = new JsonObject();
        pose.addProperty("x", p.getX());
        pose.addProperty("y", p.getY());
        pose.addProperty("z", p.getZ());
        pose.addProperty("yaw", p.getYRot());
        pose.addProperty("pitch", p.getXRot());

        // --- 360° raycast coverage ---
        JsonArray rays = new JsonArray();
        int rayCount = 16;
        double fov = 360.0;
        double maxDist = 5.0;

        for (int i = 0; i < rayCount; i++) {
            double angle = (i / (double) rayCount) * fov;
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
            double dist = hitBlock ? from.distanceTo(hit.getLocation()) : maxDist;
            r.addProperty("hit", hitBlock);
            r.addProperty("dist", dist);
            r.addProperty("angle_deg", (i / (double) rayCount) * 360.0);
            rays.add(r);
        }

        // front_clear
        double frontClearThreshold = 1.2;
        boolean frontClear = !(rays.size() > 0
            && rays.get(0).getAsJsonObject().get("dist").getAsDouble() < frontClearThreshold);

        // entities
        JsonArray entities = new JsonArray();
        int count = 0;
        for (var e : level.getEntities(p, p.getBoundingBox().inflate(8))) {
            if (count++ >= 8) break;
            JsonObject ent = new JsonObject();
            ent.addProperty("id", e.getId());
            ent.addProperty("type", e.getType().toShortString());
            ent.addProperty("dist", e.distanceTo(p));
            ent.addProperty("los", true);
            entities.add(ent);
        }

        // world
        JsonObject world = new JsonObject();
        world.addProperty("time_of_day", level.getDayTime());
        String weather = level.isThundering() ? "thunder" :
                         (level.isRaining() ? "rain" : "clear");
        world.addProperty("weather", weather);
        world.addProperty("biome",
            level.getBiome(p.blockPosition()).unwrapKey().get().location().toString());

        // inventory
        JsonArray hotbar = new JsonArray();
        var inv = p.getInventory();
        for (int i = 0; i < 9; i++) {
            var stack = inv.getItem(i);
            JsonObject item = new JsonObject();
            item.addProperty("id", stack.getItem().toString());
            item.addProperty("count", stack.getCount());
            hotbar.add(item);
        }
        // find selected slot by comparing main-hand item
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

        // collision
        JsonObject collision = new JsonObject();
        collision.addProperty("is_grounded", p.onGround());
        collision.addProperty("is_colliding", p.horizontalCollision);
        collision.addProperty("no_progress", false);

        // payload
        JsonObject payload = new JsonObject();
        payload.add("pose", pose);
        payload.add("rays", rays);
        payload.addProperty("front_clear", frontClear);
        payload.add("entities", entities);
        payload.add("world", world);
        payload.add("inventory", inventory);
        payload.add("collision", collision);

        // wrapper
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

    @SubscribeEvent
    public void onRegisterCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("aibot")
                .executes(ctx -> {
                    ctx.getSource().sendSystemMessage(Component.literal("AI bot is alive!"));
                    return 1;
                })
        );

        // /reset_episode → full reset and EPISODE_START
        event.getDispatcher().register(
            Commands.literal("reset_episode")
                .executes(ctx -> {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc != null && mc.player != null && mc.level != null) {
                        startNewEpisode(mc);
                        ctx.getSource().sendSystemMessage(
                            Component.literal("[AI-BOT] Episode reset at world spawn.")
                        );
                    }
                    return 1;
                })
        );
    }

}
