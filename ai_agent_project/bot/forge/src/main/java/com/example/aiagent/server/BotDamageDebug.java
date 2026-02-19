package com.example.aiagent.server;

import com.example.aiagent.BotMod;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = BotMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BotDamageDebug {

    // Fires when a player attempts to attack a server-side entity
    @SubscribeEvent
    public static void onAttackEntity(AttackEntityEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer attacker)) return;
        Entity target = e.getTarget();

        System.out.println("[BOT][DBG][ATTACK] attacker=" + attacker.getGameProfile().getName()
                + " attackerId=" + attacker.getId()
                + " -> targetClass=" + target.getClass().getName()
                + " targetName=" + target.getName().getString()
                + " targetId=" + target.getId()
                + " targetUuid=" + target.getUUID());
    }

    // Fires when damage actually applies to a living entity (post-calculation, can be modified/canceled)
    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent e) {
        // Log only players for signal (includes your FakePlayer)
        if (!(e.getEntity() instanceof ServerPlayer sp)) return;

        System.out.println("[BOT][DBG][HURT] target=" + sp.getGameProfile().getName()
                + " id=" + sp.getId()
                + " uuid=" + sp.getUUID()
                + " amount=" + e.getAmount()
                + " src=" + e.getSource().getMsgId()
                + " canceled=" + e.isCanceled());
    }
}
