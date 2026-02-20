package com.example.aiagent.server;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.level.ServerLevel;

public final class DamageableFakePlayerFactory {
    private DamageableFakePlayerFactory() {}

    // Always create a fresh instance.
    // Caching FakePlayers by UUID can resurrect stale/removed instances after death.
    public static DamageableFakePlayer get(ServerLevel level, GameProfile profile) {
        return new DamageableFakePlayer(level, profile);
    }
}
