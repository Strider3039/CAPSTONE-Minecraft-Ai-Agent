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

    @Override
    public boolean hurt(DamageSource source, float amount) {
        System.out.println("[BOT][DBG][OVERRIDE_HURT] called src=" + source.getMsgId() + " amt=" + amount);

        // Server-only
        if (this.level().isClientSide) {
            System.out.println("[BOT][DBG][OVERRIDE_HURT] reject: clientside");
            return false;
        }
        if (!this.isAlive()) {
            System.out.println("[BOT][DBG][OVERRIDE_HURT] reject: !alive");
            return false;
        }

        // Respect invulnerability semantics (your isInvulnerableTo override controls this)
        if (this.isInvulnerableTo(source)) {
            System.out.println("[BOT][DBG][OVERRIDE_HURT] reject: isInvulnerableTo");
            return false;
        }
        if (this.getAbilities().invulnerable) {
            System.out.println("[BOT][DBG][OVERRIDE_HURT] reject: abilities.invulnerable");
            return false;
        }
        if (this.isSpectator()) {
            System.out.println("[BOT][DBG][OVERRIDE_HURT] reject: spectator");
            return false;
        }

        // Player-only PvP gates (mobs bypass this block and can still hurt you)
        Entity atk = source.getEntity();
        if (atk instanceof Player p) {
            if (this.server != null && !this.server.isPvpAllowed()) {
                System.out.println("[BOT][DBG][OVERRIDE_HURT] reject: server pvp");
                return false;
            }
            if (!p.canHarmPlayer(this)) {
                System.out.println("[BOT][DBG][OVERRIDE_HURT] reject: attacker-side canHarmPlayer=false");
                return false;
            }
        }

        // Optional: keep vanilla-ish i-frames (uncomment if desired)
        if (this.invulnerableTime > 0) {
            System.out.println("[BOT][DBG][OVERRIDE_HURT] reject: invulnerableTime=" + this.invulnerableTime);
            return false;
        }

        // Apply damage + hurt state
        this.invulnerableTime = 20;
        this.hurtTime = 10;
        this.hurtDuration = 10;
        this.hurtMarked = true;

        System.out.println("[BOT][DBG][HURT_TIMERS] invulnTime=" + this.invulnerableTime + " hurtTime=" + this.hurtTime);

        this.actuallyHurt(source, amount);
        this.hurtPulseLatch = true;

        if (source.getEntity() instanceof LivingEntity attacker) {
            Vec3 vBeforeKB = this.getDeltaMovement();

            // Store impulse instead of relying on vanilla knockback deltaMovement surviving the tick
            double dx = attacker.getX() - this.getX();
            double dz = attacker.getZ() - this.getZ();

            // Direction away from attacker
            Vec3 dir = new Vec3(-dx, 0.0, -dz);
            if (dir.lengthSqr() > 1.0e-8) dir = dir.normalize();

            // Tune to taste (these match the feel you were logging)
            double kbH = 0.40;  // horizontal strength
            double kbY = 0.35;  // vertical pop

            Vec3 impulse = dir.scale(kbH).add(0.0, kbY, 0.0);
            this.pendingKnockbackImpulse = this.pendingKnockbackImpulse.add(impulse);

            if (this.level() instanceof ServerLevel sl) {
                this.pendingKnockbackTick = sl.getGameTime();
            }

            System.out.println("[BOT][DBG][KB-PENDING] tick=" + this.pendingKnockbackTick
                    + " impulse=" + impulse
                    + " pendingSum=" + this.pendingKnockbackImpulse);
            this.knockbackLockTicks = 2; // 1–2 ticks is enough; 2 is safer visually

            Vec3 vNow = this.getDeltaMovement();
            Vec3 vWouldBe = vNow.add(impulse);

            if (this.level() instanceof ServerLevel sl) {
                this.lastKnockbackServerTick = sl.getGameTime();
            }
            this.lastKnockbackVelAfter = vWouldBe;

            System.out.println("[BOT][DBG][KB-STAMP] tick=" + this.lastKnockbackServerTick
                    + " dmNow=" + vNow
                    + " impulse=" + impulse
                    + " dmWouldBe=" + vWouldBe);
        }

        this.level().broadcastEntityEvent(this, (byte)2);

        if (atk instanceof LivingEntity le) {
            this.setLastHurtByMob(le);
        }

        System.out.println("[BOT][DBG][OVERRIDE_HURT] applied newHealth=" + this.getHealth());
        return true;
    }

}
