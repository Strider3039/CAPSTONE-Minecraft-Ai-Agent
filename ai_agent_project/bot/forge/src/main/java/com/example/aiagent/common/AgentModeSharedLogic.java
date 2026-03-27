package com.example.aiagent.common;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

public final class AgentModeSharedLogic {

    private AgentModeSharedLogic() {}

    public record RaySample(boolean hit, double dist, double angleDeg) {}

    public record HotbarSlot(String id, int count) {}

    public record NearbyEntitySample(int id, String type, double dist, boolean los) {}

    public record DecodedAction(
            boolean hasLook,
            boolean hasMove,
            boolean refreshHold,
            float yawDelta,
            float pitchDelta,
            float forward,
            float strafe,
            boolean jump,
            boolean sprint,
            boolean sneak,
            int holdTicks,
            int selectSlot,
            boolean attack,
            boolean use,
            int equipArmorFromSlot,
            int swapSelectedFromSlot,
            int dropFromSlot,
            int dropCount
    ) {}

    public interface ObservationAdapter {
        double x();
        double y();
        double z();
        float yaw();
        float pitch();
        List<RaySample> rays();
        double timeOfDay();
        String weather();
        String biome();
        int selectedSlot();
        List<HotbarSlot> hotbar();
        boolean isGrounded();
        boolean isColliding();
        boolean noProgress();
        List<NearbyEntitySample> nearbyEntities();
    }

    public interface EpisodeResetAdapter {
        void ensureAlive();
        void teleportToSpawn();
        void restoreVitals();
        void clearInventory();
        void clearEquipment();
        void clearFire();
        void clearEffects();
        void resetAir();
        void resetFallDistance();
        void clearVelocity();
        void resetWorldState();
        void markEpisodeStarted();
    }

    public static JsonObject buildObservationPayload(ObservationAdapter adapter) {
        JsonObject payload = new JsonObject();

        JsonObject pose = new JsonObject();
        pose.addProperty("x", adapter.x());
        pose.addProperty("y", adapter.y());
        pose.addProperty("z", adapter.z());
        pose.addProperty("yaw", adapter.yaw());
        pose.addProperty("pitch", adapter.pitch());
        payload.add("pose", pose);

        JsonArray rays = new JsonArray();
        for (RaySample ray : adapter.rays()) {
            JsonObject item = new JsonObject();
            item.addProperty("hit", ray.hit());
            item.addProperty("dist", ray.dist());
            item.addProperty("angle_deg", ray.angleDeg());
            rays.add(item);
        }
        payload.add("rays", rays);
        payload.addProperty("front_clear", isFrontClear(adapter.rays(), 1.25));

        JsonObject world = new JsonObject();
        world.addProperty("time_of_day", adapter.timeOfDay());
        world.addProperty("weather", safeString(adapter.weather(), "clear"));
        world.addProperty("biome", safeString(adapter.biome(), "unknown"));
        payload.add("world", world);

        JsonObject inventory = new JsonObject();
        inventory.addProperty("selected_slot", adapter.selectedSlot());
        JsonArray hotbar = new JsonArray();
        for (HotbarSlot slot : adapter.hotbar()) {
            JsonObject item = new JsonObject();
            item.addProperty("id", safeString(slot.id(), "minecraft:air"));
            item.addProperty("count", slot.count());
            hotbar.add(item);
        }
        inventory.add("hotbar", hotbar);
        payload.add("inventory", inventory);

        JsonObject collision = new JsonObject();
        collision.addProperty("is_grounded", adapter.isGrounded());
        collision.addProperty("is_colliding", adapter.isColliding());
        collision.addProperty("no_progress", adapter.noProgress());
        payload.add("collision", collision);

        JsonArray entities = new JsonArray();
        for (NearbyEntitySample entity : adapter.nearbyEntities()) {
            JsonObject item = new JsonObject();
            item.addProperty("id", entity.id());
            item.addProperty("type", safeString(entity.type(), "unknown"));
            item.addProperty("dist", entity.dist());
            item.addProperty("los", entity.los());
            entities.add(item);
        }
        payload.add("entities", entities);

        return payload;
    }

    public static void applyEpisodeReset(EpisodeResetAdapter adapter) {
        if (adapter == null) return;
        adapter.ensureAlive();
        adapter.teleportToSpawn();
        adapter.restoreVitals();
        adapter.clearInventory();
        adapter.clearEquipment();
        adapter.clearFire();
        adapter.clearEffects();
        adapter.resetAir();
        adapter.resetFallDistance();
        adapter.clearVelocity();
        adapter.resetWorldState();
        adapter.markEpisodeStarted();
    }

    public static DecodedAction decodeActionPayload(
            JsonObject payload,
            float maxYawPerTick,
            float maxPitchPerTick,
            int holdTicks
    ) {
        if (payload == null) {
            return new DecodedAction(false, false, false, 0f, 0f, 0f, 0f,
                    false, false, false, Math.max(1, holdTicks), -1, false, false,
                    -1, -1, -1, 1);
        }

        boolean hasLook = payload.has("look") && payload.get("look").isJsonObject();
        boolean hasMove = payload.has("move") && payload.get("move").isJsonObject();

        float yawDelta = 0f;
        float pitchDelta = 0f;
        if (hasLook) {
            JsonObject look = payload.getAsJsonObject("look");
            yawDelta = firstFloat(look, "dYaw", "yaw_delta", "dyaw");
            pitchDelta = firstFloat(look, "dPitch", "pitch_delta", "dpitch");
        }

        float forward = 0f;
        float strafe = 0f;
        boolean jump = false;
        boolean sprint = false;
        boolean sneak = false;
        if (hasMove) {
            JsonObject move = payload.getAsJsonObject("move");
            forward = move.has("forward") ? (float) move.get("forward").getAsDouble() : 0f;
            strafe = move.has("strafe") ? (float) move.get("strafe").getAsDouble() : 0f;
            jump = move.has("jump") && move.get("jump").getAsBoolean();
            sprint = move.has("sprint") && move.get("sprint").getAsBoolean();
            sneak = move.has("sneak") && move.get("sneak").getAsBoolean();
        }

        if (payload.has("jump")) jump = payload.get("jump").getAsBoolean();
        if (payload.has("sprint")) sprint = payload.get("sprint").getAsBoolean();
        if (payload.has("sneak")) sneak = payload.get("sneak").getAsBoolean();

        int selectSlot = payload.has("select_slot") ? payload.get("select_slot").getAsInt() : -1;
        boolean attack = payload.has("attack") && payload.get("attack").getAsBoolean();
        boolean use = payload.has("use") && payload.get("use").getAsBoolean();
        int equipArmorFromSlot = payload.has("equip_armor_from_slot") ? payload.get("equip_armor_from_slot").getAsInt() : -1;
        int swapSelectedFromSlot = payload.has("swap_selected_from_slot") ? payload.get("swap_selected_from_slot").getAsInt() : -1;
        int dropFromSlot = -1;
        int dropCount = 1;
        if (payload.has("drop_slot") && payload.get("drop_slot").isJsonObject()) {
            JsonObject drop = payload.getAsJsonObject("drop_slot");
            dropFromSlot = drop.has("slot") ? drop.get("slot").getAsInt() : -1;
            dropCount = drop.has("count") ? drop.get("count").getAsInt() : 1;
        }

        boolean refreshHold = hasLook || hasMove || payload.has("jump") || payload.has("sprint") || payload.has("sneak");

        return new DecodedAction(
                hasLook,
                hasMove,
                refreshHold,
                clamp(yawDelta, -maxYawPerTick, maxYawPerTick),
                clamp(pitchDelta, -maxPitchPerTick, maxPitchPerTick),
                forward,
                strafe,
                jump,
                sprint,
                sneak,
                Math.max(1, holdTicks),
                selectSlot,
                attack,
                use,
                equipArmorFromSlot,
                swapSelectedFromSlot,
                dropFromSlot,
                Math.max(1, dropCount)
        );
    }

    public static boolean isFrontClear(List<RaySample> rays, double threshold) {
        if (rays == null || rays.isEmpty()) return true;
        RaySample ray = rays.get(0);
        return !(ray.hit() && ray.dist() < threshold);
    }

    private static float firstFloat(JsonObject json, String... keys) {
        if (json == null) return 0f;
        for (String key : keys) {
            if (json.has(key)) {
                return json.get(key).getAsFloat();
            }
        }
        return 0f;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String safeString(String s, String fallback) {
        return (s == null || s.isBlank()) ? fallback : s;
    }
}
