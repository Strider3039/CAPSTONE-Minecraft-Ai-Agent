package com.example.aiagent.server;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

public class DamageableFakePlayer extends FakePlayer {

    public DamageableFakePlayer(ServerLevel level, GameProfile profile) {
        super(level, profile);
    }

    // One-tick pulse latch for client ghost visuals
    public boolean hurtPulseLatch = false;
    public int knockbackLockTicks = 0;
    public long lastKnockbackServerTick = -1;
    public Vec3 lastKnockbackVelAfter = Vec3.ZERO;
    public Vec3 pendingKnockbackImpulse = Vec3.ZERO;
    public long pendingKnockbackTick = -1;

    @Override
    public boolean isInvulnerableTo(DamageSource src) {
        // Keep vanilla bypass semantics (void damage etc.)
        if (src.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return false;

        // Respect explicit invulnerability flags if ever enabled
        if (this.isInvulnerable()) return true;
        if (this.getAbilities().invulnerable) return true;
        if (this.isSpectator()) return true;

        // Allow damage like a normal survival player
        return false;
    }

    @Override
    public void tick() {
        Vec3 pre = this.getDeltaMovement();
        double preH = Math.hypot(pre.x, pre.z);

        super.tick();

        Vec3 post = this.getDeltaMovement();
        double postH = Math.hypot(post.x, post.z);

        // Only print when something interesting happens (knockback-sized changes)
        if (preH > 0.25 || postH > 0.25 || (preH > 0.10 && postH < 0.10)) {
            System.out.println("[BOT][DBG][TICK_VEL] gt=" + ((ServerLevel)this.level()).getGameTime()
                    + " pre=" + pre + " post=" + post
                    + " onGround=" + this.onGround()
                    + " kbLock=" + this.knockbackLockTicks);
        }
    }

    @SuppressWarnings("null")
    @Override
    public boolean hurt(DamageSource source, float amount) {
        // Server-only + basic validity
        if (this.level().isClientSide) return false;
        if (!this.isAlive()) return false;

        // Respect invulnerability semantics (your isInvulnerableTo override controls this)
        if (this.isInvulnerableTo(source)) return false;
        if (this.getAbilities().invulnerable) return false;
        if (this.isSpectator()) return false;

        // Player-only PvP gates (non-player sources like fall/fire should still work)
        Entity atk = source.getEntity();
        if (atk instanceof Player p) {
            if (this.server != null && !this.server.isPvpAllowed()) return false;
            if (!p.canHarmPlayer(this)) return false;
        }

        // Vanilla-ish i-frames (keep if you want hit immunity)
        if (this.invulnerableTime > 0) return false;

        // Apply hurt timers (client ghost uses these + your pulse)
        this.invulnerableTime = 20;
        this.hurtTime = 10;
        this.hurtDuration = 10;
        this.hurtMarked = true;

        // Apply damage (handles armor/effects/etc depending on source)
        this.actuallyHurt(source, amount);

        // One-shot pulse for client-side red flash/animation
        this.hurtPulseLatch = true;

        // Deterministic knockback impulse only when there is a living attacker
        if (atk instanceof LivingEntity attacker) {
            double dx = attacker.getX() - this.getX();
            double dz = attacker.getZ() - this.getZ();

            // Direction away from attacker
            Vec3 dir = new Vec3(-dx, 0.0, -dz);
            if (dir.lengthSqr() > 1.0e-8) dir = dir.normalize();

            // Tune to taste
            double kbH = 0.40;  // horizontal strength
            double kbY = 0.35;  // vertical pop

            Vec3 impulse = dir.scale(kbH).add(0.0, kbY, 0.0);
            this.pendingKnockbackImpulse = this.pendingKnockbackImpulse.add(impulse);

            if (this.level() instanceof ServerLevel sl) {
                this.pendingKnockbackTick = sl.getGameTime();
            }

            // Prevent RL travel from clobbering the impulse for a couple ticks
            this.knockbackLockTicks = 2;

            this.setLastHurtByMob(attacker);
        }

        // Vanilla hurt event (plays sounds/particles and supports client-side animation handling)
        this.level().broadcastEntityEvent(this, (byte) 2);

        System.out.println("[BOT][DBG][OVERRIDE_HURT] applied newHealth=" + this.getHealth());
        return true;
    }

}
