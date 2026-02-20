package com.example.aiagent.server;
import com.example.aiagent.BotMod;
import com.example.aiagent.server.DamageableFakePlayer;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = BotMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BotLifecycleEvents {

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer sp)) return;
        if (!(sp instanceof DamageableFakePlayer)) return;

        FakeBotManager mgr = BotMod.getInstance().getBotManager();
        if (mgr == null) return;

        mgr.onBotDied(sp, sp.position());
    }
}
