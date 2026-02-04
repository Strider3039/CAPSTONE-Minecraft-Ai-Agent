package com.example.aiagent.client;

import com.example.aiagent.BotMod;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

public final class EpisodeController {

    private EpisodeController() {}

    public static void startNewEpisode(Minecraft mc) {
        Player p = mc.player;
        Level level = mc.level;
        if (p == null || level == null) return;

        if (!p.isAlive()) {
            System.out.println("[AI-BOT] Episode start requested but player is not alive. Ignoring.");
            return;
        }

        BlockPos spawn = level.getSharedSpawnPos();
        p.teleportTo(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);

        p.setHealth(p.getMaxHealth());
        p.getFoodData().setFoodLevel(20);
        p.getFoodData().setSaturation(5.0f);
        p.getInventory().clearContent();

        // ✅ update common episode state without client types in BotMod
        BotMod inst = BotMod.getInstance();
        if (inst != null) inst.markEpisodeStarted(level.getGameTime());
    }
}
