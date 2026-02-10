package com.example.aiagent.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record S2CBotStatePacket(
        String botId,
        double x, double y, double z,
        float yaw, float pitch,
        boolean onGround
) {
    public static void encode(S2CBotStatePacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.botId, 64);
        buf.writeDouble(msg.x);
        buf.writeDouble(msg.y);
        buf.writeDouble(msg.z);
        buf.writeFloat(msg.yaw);
        buf.writeFloat(msg.pitch);
        buf.writeBoolean(msg.onGround);
    }

    public static S2CBotStatePacket decode(FriendlyByteBuf buf) {
        return new S2CBotStatePacket(
                buf.readUtf(64),
                buf.readDouble(), buf.readDouble(), buf.readDouble(),
                buf.readFloat(), buf.readFloat(),
                buf.readBoolean()
        );
    }

    public static void handle(S2CBotStatePacket msg, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        ctx.enqueueWork(() -> {
            // Only run on client
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
                com.example.aiagent.client.ClientGhostBots.onBotState(msg);
            });
        });
        ctx.setPacketHandled(true);
    }
}
