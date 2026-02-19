package com.example.aiagent.server;

import com.example.aiagent.BotMod;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.eventbus.api.EventPriority;


@Mod.EventBusSubscriber(modid = BotMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BotDamageDebug {

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onAttackEntityPost(AttackEntityEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer attacker)) return;
        if (attacker.level().isClientSide) return;

        System.out.println("[BOT][DBG][ATTACK_POST] canceled=" + e.isCanceled()
                + " targetId=" + e.getTarget().getId()
                + " targetClass=" + e.getTarget().getClass().getName());
    }

    @SubscribeEvent
    public static void onLivingAttack(LivingAttackEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer sp)) return;

        System.out.println("[BOT][DBG][ATTACK_EVT] target=" + sp.getGameProfile().getName()
                + " id=" + sp.getId()
                + " amount=" + e.getAmount()
                + " src=" + e.getSource().getMsgId()
                + " attacker=" + (e.getSource().getEntity() != null ? e.getSource().getEntity().getName().getString() : "null")
                + " direct=" + (e.getSource().getDirectEntity() != null ? e.getSource().getDirectEntity().getName().getString() : "null")
                + " canceled=" + e.isCanceled());
    }

    @SubscribeEvent
    public static void onLivingDamage(LivingDamageEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer sp)) return;

        System.out.println("[BOT][DBG][DAMAGE] target=" + sp.getGameProfile().getName()
                + " id=" + sp.getId()
                + " amount=" + e.getAmount()
                + " src=" + e.getSource().getMsgId()
                + " canceled=" + e.isCanceled());
    }


    @SubscribeEvent
    public static void onAttackEntity(AttackEntityEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer attacker)) return;

        boolean client = attacker.level().isClientSide;
        System.out.println("[BOT][DBG][ATTACK] side=" + (client ? "CLIENT" : "SERVER"));
        if (client) return;

        Entity target = e.getTarget();

        System.out.println("[BOT][DBG][ATTACK] attacker=" + attacker.getGameProfile().getName()
                + " attackerId=" + attacker.getId()
                + " -> targetClass=" + target.getClass().getName()
                + " targetName=" + target.getName().getString()
                + " targetId=" + target.getId()
                + " targetUuid=" + target.getUUID());

        if (target instanceof net.minecraft.world.entity.LivingEntity le) {
            var ds = attacker.damageSources().playerAttack(attacker);
            System.out.println("[BOT][DBG][INVULN] target.isInvulnerableTo(playerAttack)=" + le.isInvulnerableTo(ds));
        }

        if (target instanceof net.minecraft.world.entity.player.Player tp) {
            var at = attacker.getTeam();
            var tt = tp.getTeam();

            System.out.println("[BOT][DBG][PVP] server.isPvpAllowed=" + attacker.server.isPvpAllowed()
                    + " attacker.canHarmPlayer=" + attacker.canHarmPlayer(tp)
                    + " attackerTeam=" + (at != null ? at.getName() : "null")
                    + " targetTeam=" + (tt != null ? tt.getName() : "null")
                    + " sameTeam=" + (at != null && at == tt)
                    + " friendlyFire=" + (at != null ? at.isAllowFriendlyFire() : true)
                    + " targetCreative=" + tp.isCreative()
                    + " targetSpectator=" + tp.isSpectator()
                    + " targetInvulnFlag=" + tp.isInvulnerable()
                    + " targetAbilityInvuln=" + (tp instanceof ServerPlayer sp ? sp.getAbilities().invulnerable : false));
        }

        if (target instanceof net.minecraft.world.entity.LivingEntity le) {
            System.out.println("[BOT][DBG][HURT_STATE] alive=" + le.isAlive()
                    + " health=" + le.getHealth()
                    + " invulnTime=" + le.invulnerableTime
                    + " hurtTime=" + le.hurtTime);

            if (target instanceof net.minecraft.world.entity.player.Player tp) {
                System.out.println("[BOT][DBG][PLAYER_STATE] target.canHarmPlayer(attacker)="
                        + tp.canHarmPlayer(attacker)
                        + " sleeping=" + tp.isSleeping()
                        + " abilitiesInvuln=" + (tp instanceof net.minecraft.server.level.ServerPlayer sp ? sp.getAbilities().invulnerable : false));
            }


            var ds = attacker.damageSources().playerAttack(attacker);
            boolean applied = le.hurt(ds, 6.0f);
            System.out.println("[BOT][DBG][FORCE_HURT] applied=" + applied
                    + " newHealth=" + le.getHealth()
                    + " invulnTimeAfter=" + le.invulnerableTime
                    + " hurtTimeAfter=" + le.hurtTime);
        }

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
