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

public class DamageableFakePlayer extends FakePlayer {

    public DamageableFakePlayer(ServerLevel level, GameProfile profile) {
        super(level, profile);
    }

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
    public boolean hurt(DamageSource source, float amount) {
        System.out.println("[BOT][DBG][OVERRIDE_HURT] called src=" + source.getMsgId() + " amt=" + amount);

        if (this.level().isClientSide) { System.out.println("[BOT][DBG][OVERRIDE_HURT] reject: clientside"); return false; }
        if (!this.isAlive()) { System.out.println("[BOT][DBG][OVERRIDE_HURT] reject: !alive"); return false; }

        if (this.isInvulnerableTo(source)) { System.out.println("[BOT][DBG][OVERRIDE_HURT] reject: isInvulnerableTo"); return false; }
        if (this.getAbilities().invulnerable) { System.out.println("[BOT][DBG][OVERRIDE_HURT] reject: abilities.invulnerable"); return false; }
        if (this.isSpectator()) { System.out.println("[BOT][DBG][OVERRIDE_HURT] reject: spectator"); return false; }

        Entity atk = source.getEntity();
        if (atk instanceof Player p) {
            if (this.server != null && !this.server.isPvpAllowed()) { System.out.println("[BOT][DBG][OVERRIDE_HURT] reject: server pvp"); return false; }
            if (!this.canHarmPlayer(p)) { System.out.println("[BOT][DBG][OVERRIDE_HURT] reject: !canHarmPlayer(attacker)"); return false; }
        }

        // Apply damage
        this.invulnerableTime = 20;
        this.hurtTime = 10;
        this.hurtDuration = 10;
        this.hurtMarked = true;

        this.actuallyHurt(source, amount);
        System.out.println("[BOT][DBG][OVERRIDE_HURT] applied newHealth=" + this.getHealth());
        return true;
    }


}
