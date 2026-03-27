package com.example.aiagent.client;

import com.example.aiagent.BotMod;
import com.example.aiagent.common.AgentModeSharedLogic;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public final class EpisodeController {

    private EpisodeController() {}

    public static void startNewEpisode(Minecraft mc) {
        Player p = mc.player;
        Level level = mc.level;
        if (p == null || level == null) return;
        AgentModeSharedLogic.applyEpisodeReset(new AgentModeSharedLogic.EpisodeResetAdapter() {
            @Override
            public void ensureAlive() {
                if (!p.isAlive()) {
                    try { p.respawn(); } catch (Exception ignored) {}
                }
            }

            @Override
            public void teleportToSpawn() {
                BlockPos spawn = level.getSharedSpawnPos();
                p.teleportTo(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);
            }

            @Override
            public void restoreVitals() {
                p.setHealth(p.getMaxHealth());
                p.getFoodData().setFoodLevel(20);
                p.getFoodData().setSaturation(5.0f);
            }

            @Override
            public void clearInventory() {
                p.getInventory().clearContent();
                p.getInventory().selected = 0;
            }

            @Override
            public void clearEquipment() {
                p.setItemSlot(net.minecraft.world.entity.EquipmentSlot.FEET, net.minecraft.world.item.ItemStack.EMPTY);
                p.setItemSlot(net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.item.ItemStack.EMPTY);
                p.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, net.minecraft.world.item.ItemStack.EMPTY);
                p.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, net.minecraft.world.item.ItemStack.EMPTY);
                p.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND, net.minecraft.world.item.ItemStack.EMPTY);
            }

            @Override
            public void clearFire() {
                p.clearFire();
            }

            @Override
            public void clearEffects() {
                p.removeAllEffects();
            }

            @Override
            public void resetAir() {
                p.setAirSupply(p.getMaxAirSupply());
            }

            @Override
            public void resetFallDistance() {
                p.fallDistance = 0.0f;
            }

            @Override
            public void clearVelocity() {
                p.setDeltaMovement(Vec3.ZERO);
            }

            @Override
            public void resetWorldState() {
                try {
                    var server = mc.getSingleplayerServer();
                    if (server != null) {
                        ServerLevel overworld = server.overworld();
                        if (overworld != null) {
                            overworld.setDayTime(0);
                            overworld.setWeatherParameters(6000, 0, false, false);
                        }
                    }
                } catch (Exception ignored) {
                }
            }

            @Override
            public void markEpisodeStarted() {
                BotMod inst = BotMod.getInstance();
                if (inst != null) inst.markEpisodeStarted(level.getGameTime());
            }
        });
    }
}
