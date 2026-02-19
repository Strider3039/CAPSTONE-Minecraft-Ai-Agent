package com.example.aiagent.server;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.level.ServerLevel;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class DamageableFakePlayerFactory {
    private DamageableFakePlayerFactory() {}

    // (uuid) -> cached fake player
    private static final Map<UUID, WeakReference<DamageableFakePlayer>> CACHE = new ConcurrentHashMap<>();

    public static DamageableFakePlayer get(ServerLevel level, GameProfile profile) {
        UUID key = profile.getId();

        WeakReference<DamageableFakePlayer> ref = CACHE.get(key);
        DamageableFakePlayer fp = (ref != null) ? ref.get() : null;

        if (fp == null || fp.level() != level) {
            fp = new DamageableFakePlayer(level, profile);
            CACHE.put(key, new WeakReference<>(fp));
        }
        return fp;
    }
}
