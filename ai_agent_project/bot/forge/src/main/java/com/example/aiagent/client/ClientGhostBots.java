package com.example.aiagent.client;

import com.example.aiagent.net.S2CBotStatePacket;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Entity.RemovalReason;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class ClientGhostBots {
    private static final Map<String, RemotePlayer> ghosts = new HashMap<>();
    private static ClientLevel lastLevel;

    private ClientGhostBots() {}

    public static void onBotState(S2CBotStatePacket s) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;

        // Clear cached ghosts when switching worlds/servers/dimensions
        if (lastLevel != level) {
            ghosts.clear();
            lastLevel = level;
            System.out.println("[AI-BOT][CLIENT] Level changed, cleared ghosts.");
        }

        RemotePlayer ghost = ghosts.get(s.botId());
        if (ghost == null) {
            ghost = spawnGhost(level, s);
            ghosts.put(s.botId(), ghost);
            System.out.println("[AI-BOT][CLIENT] Spawned ghost: " + s.botId());
        }

        // Smooth-ish update
        ghost.lerpTo(s.x(), s.y(), s.z(), s.yaw(), s.pitch(), 3, true);
        ghost.setOnGround(s.onGround());

        // Keep head rotation aligned with body yaw for better visuals
        ghost.setYHeadRot(s.yaw());
        // Some mappings allow this field; if yours doesn't, just delete this line.
        ghost.yHeadRotO = s.yaw();
    }

    private static RemotePlayer spawnGhost(ClientLevel level, S2CBotStatePacket s) {
        UUID uuid = UUID.nameUUIDFromBytes(("bot:" + s.botId()).getBytes(StandardCharsets.UTF_8));
        GameProfile profile = new GameProfile(uuid, s.botId());

        RemotePlayer ghost = new RemotePlayer(level, profile);

        int entityId = stableEntityId(s.botId());
        ghost.setId(entityId);

        ghost.setPos(s.x(), s.y(), s.z());
        ghost.setYRot(s.yaw());
        ghost.setXRot(s.pitch());
        ghost.setYHeadRot(s.yaw());
        ghost.yHeadRotO = s.yaw();

        // If an entity with this ID already exists, remove it first (prevents duplicates)
        Entity existing = level.getEntity(entityId);
        if (existing != null) {
            existing.remove(RemovalReason.DISCARDED);
        }

        // IMPORTANT: Player entities must be added with addPlayer (not addEntity)
        level.addPlayer(entityId, ghost);

        return ghost;
    }

    private static int stableEntityId(String botId) {
        return 0x3FFF0000 ^ botId.hashCode();
    }
}
