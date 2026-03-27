package com.example.aiagent.common;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentModeSharedLogicTest {

    @Test
    void decodeActionPayload_normalizesLegacyAndOverrideFields() {
        JsonObject payload = new JsonObject();

        JsonObject look = new JsonObject();
        look.addProperty("dYaw", 99.0f);
        look.addProperty("pitch_delta", -30.0f);
        payload.add("look", look);

        JsonObject move = new JsonObject();
        move.addProperty("forward", 1.0f);
        move.addProperty("strafe", -0.75f);
        move.addProperty("jump", false);
        move.addProperty("sprint", false);
        payload.add("move", move);

        payload.addProperty("jump", true);
        payload.addProperty("sneak", true);
        payload.addProperty("select_slot", 4);
        payload.addProperty("attack", true);
        payload.addProperty("use", false);
        payload.addProperty("equip_armor_from_slot", 12);
        payload.addProperty("swap_selected_from_slot", 7);

        JsonObject drop = new JsonObject();
        drop.addProperty("slot", 5);
        drop.addProperty("count", 3);
        payload.add("drop_slot", drop);

        AgentModeSharedLogic.DecodedAction action =
                AgentModeSharedLogic.decodeActionPayload(payload, 15.0f, 10.0f, 3);

        assertTrue(action.hasLook());
        assertTrue(action.hasMove());
        assertTrue(action.refreshHold());
        assertEquals(15.0f, action.yawDelta());
        assertEquals(-10.0f, action.pitchDelta());
        assertEquals(1.0f, action.forward());
        assertEquals(-0.75f, action.strafe());
        assertTrue(action.jump());
        assertFalse(action.sprint());
        assertTrue(action.sneak());
        assertEquals(3, action.holdTicks());
        assertEquals(4, action.selectSlot());
        assertTrue(action.attack());
        assertFalse(action.use());
        assertEquals(12, action.equipArmorFromSlot());
        assertEquals(7, action.swapSelectedFromSlot());
        assertEquals(5, action.dropFromSlot());
        assertEquals(3, action.dropCount());
    }

    @Test
    void buildObservationPayload_writesBridgeShapeFromAdapter() {
        JsonObject payload = AgentModeSharedLogic.buildObservationPayload(new AgentModeSharedLogic.ObservationAdapter() {
            @Override public double x() { return 1.25; }
            @Override public double y() { return 64.0; }
            @Override public double z() { return -3.5; }
            @Override public float yaw() { return 90.0f; }
            @Override public float pitch() { return -12.5f; }
            @Override public List<AgentModeSharedLogic.RaySample> rays() {
                return List.of(
                        new AgentModeSharedLogic.RaySample(true, 0.75, 0.0),
                        new AgentModeSharedLogic.RaySample(false, 5.0, 22.5)
                );
            }
            @Override public double timeOfDay() { return 6000.0; }
            @Override public String weather() { return "rain"; }
            @Override public String biome() { return "minecraft:plains"; }
            @Override public int selectedSlot() { return 2; }
            @Override public List<AgentModeSharedLogic.HotbarSlot> hotbar() {
                return List.of(
                        new AgentModeSharedLogic.HotbarSlot("minecraft:stone", 12),
                        new AgentModeSharedLogic.HotbarSlot("minecraft:air", 0)
                );
            }
            @Override public boolean isGrounded() { return true; }
            @Override public boolean isColliding() { return false; }
            @Override public boolean noProgress() { return true; }
            @Override public List<AgentModeSharedLogic.NearbyEntitySample> nearbyEntities() {
                return List.of(new AgentModeSharedLogic.NearbyEntitySample(9, "minecraft:zombie", 2.5, true));
            }
        });

        assertEquals(1.25, payload.getAsJsonObject("pose").get("x").getAsDouble());
        assertEquals(-12.5f, payload.getAsJsonObject("pose").get("pitch").getAsFloat());

        JsonArray rays = payload.getAsJsonArray("rays");
        assertEquals(2, rays.size());
        assertTrue(rays.get(0).getAsJsonObject().get("hit").getAsBoolean());
        assertFalse(payload.get("front_clear").getAsBoolean());

        JsonObject world = payload.getAsJsonObject("world");
        assertEquals("rain", world.get("weather").getAsString());
        assertEquals("minecraft:plains", world.get("biome").getAsString());

        JsonArray hotbar = payload.getAsJsonObject("inventory").getAsJsonArray("hotbar");
        assertEquals("minecraft:stone", hotbar.get(0).getAsJsonObject().get("id").getAsString());
        assertEquals(12, hotbar.get(0).getAsJsonObject().get("count").getAsInt());

        assertTrue(payload.getAsJsonObject("collision").get("no_progress").getAsBoolean());
        assertEquals("minecraft:zombie",
                payload.getAsJsonArray("entities").get(0).getAsJsonObject().get("type").getAsString());
    }

    @Test
    void applyEpisodeReset_invokesAllAdapterHooks() {
        List<String> calls = new ArrayList<>();

        AgentModeSharedLogic.applyEpisodeReset(new AgentModeSharedLogic.EpisodeResetAdapter() {
            @Override public void ensureAlive() { calls.add("ensureAlive"); }
            @Override public void teleportToSpawn() { calls.add("teleportToSpawn"); }
            @Override public void restoreVitals() { calls.add("restoreVitals"); }
            @Override public void clearInventory() { calls.add("clearInventory"); }
            @Override public void clearEquipment() { calls.add("clearEquipment"); }
            @Override public void clearFire() { calls.add("clearFire"); }
            @Override public void clearEffects() { calls.add("clearEffects"); }
            @Override public void resetAir() { calls.add("resetAir"); }
            @Override public void resetFallDistance() { calls.add("resetFallDistance"); }
            @Override public void clearVelocity() { calls.add("clearVelocity"); }
            @Override public void resetWorldState() { calls.add("resetWorldState"); }
            @Override public void markEpisodeStarted() { calls.add("markEpisodeStarted"); }
        });

        assertEquals(List.of(
                "ensureAlive",
                "teleportToSpawn",
                "restoreVitals",
                "clearInventory",
                "clearEquipment",
                "clearFire",
                "clearEffects",
                "resetAir",
                "resetFallDistance",
                "clearVelocity",
                "resetWorldState",
                "markEpisodeStarted"
        ), calls);
    }
}
