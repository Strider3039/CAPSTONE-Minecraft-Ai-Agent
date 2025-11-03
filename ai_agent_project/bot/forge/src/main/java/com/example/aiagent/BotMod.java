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

@Mod(BotMod.MODID)
public class BotMod {

    public static final String MODID = "ai_agent_bot";
    private static BotMod INSTANCE;
    public static BotMod getInstance() { return INSTANCE; }

    public static final Gson GSON = new GsonBuilder().create();
    private ForgeWebSocketClient wsClient;
    private boolean triedConnect = false;

    private final ConcurrentHashMap<Long, Long> latencyMap = new ConcurrentHashMap<>();

    private static final KeyMapping TOGGLE_KEY =
        new KeyMapping("key.aibot.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_P, "key.categories.misc");

    private boolean aiEnabled = true;
    private long lastSendMs = 0;
    private static final long MIN_SEND_INTERVAL_MS = 70; // ~14 Hz
    private long reconnectCount = 0;
    private long droppedCount = 0;

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
                    sendObservation(mc);
                });
                System.out.println("[AI-BOT] Connecting to AI bridge…");
            } catch (Exception e) {
                System.err.println("[AI-BOT] Failed to connect:");
                e.printStackTrace();
            }
        }

        // toggle enable/disable
        if (TOGGLE_KEY.consumeClick()) {
            aiEnabled = !aiEnabled;
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
        JsonArray rays = new JsonArray();  // ✅ <-- You need this line
        int rayCount = 16;                 // more rays = smoother spatial awareness
        double fov = 360.0;                // full circle around player
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
    }

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(TOGGLE_KEY);
    }
}
