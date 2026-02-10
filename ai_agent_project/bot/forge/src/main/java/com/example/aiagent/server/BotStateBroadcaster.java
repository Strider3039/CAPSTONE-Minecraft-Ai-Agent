package com.example.aiagent.server;

import com.example.aiagent.net.BotNet;
import com.example.aiagent.net.S2CBotStatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.network.PacketDistributor;

public final class BotStateBroadcaster {
    private BotStateBroadcaster() {}

    public static void send(ServerLevel level, Player bot, String botId) {
        S2CBotStatePacket msg = new S2CBotStatePacket(
                botId,
                bot.getX(), bot.getY(), bot.getZ(),
                bot.getYRot(), bot.getXRot(),
                bot.onGround()
        );

        // easiest while debugging:
        BotNet.CHANNEL.send(PacketDistributor.DIMENSION.with(level::dimension), msg);

        // once stable, better bandwidth:
        // BotNet.CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> bot), msg);
    }
}
