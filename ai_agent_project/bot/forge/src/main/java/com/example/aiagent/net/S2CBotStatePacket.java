package com.example.aiagent.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record S2CBotStatePacket(
        String botId,
        long serverTick,
        double x, double y, double z,
        double vx, double vy, double vz,
        float yaw, float pitch,
        boolean onGround,
        boolean swingMainHandPulse
) {
    public static void encode(S2CBotStatePacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.botId, 64);
        buf.writeLong(msg.serverTick);

        buf.writeDouble(msg.x);
        buf.writeDouble(msg.y);
        buf.writeDouble(msg.z);

        buf.writeDouble(msg.vx);
        buf.writeDouble(msg.vy);
        buf.writeDouble(msg.vz);

        buf.writeFloat(msg.yaw);
        buf.writeFloat(msg.pitch);

        buf.writeBoolean(msg.onGround);
        buf.writeBoolean(msg.swingMainHandPulse);
    }

    public static S2CBotStatePacket decode(FriendlyByteBuf buf) {
        String botId = buf.readUtf(64);
        long serverTick = buf.readLong();

        double x = buf.readDouble();
        double y = buf.readDouble();
        double z = buf.readDouble();

        double vx = buf.readDouble();
        double vy = buf.readDouble();
        double vz = buf.readDouble();

        float yaw = buf.readFloat();
        float pitch = buf.readFloat();

        boolean onGround = buf.readBoolean();
        boolean swingMainHandPulse = buf.readBoolean();

        return new S2CBotStatePacket(
                botId,
                serverTick,
                x, y, z,
                vx, vy, vz,
                yaw, pitch,
                onGround,
                swingMainHandPulse
        );
    }

    public static void handle(S2CBotStatePacket msg, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        ctx.enqueueWork(() -> {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
                com.example.aiagent.client.ClientGhostBots.onBotState(msg);
            });
        });
        ctx.setPacketHandled(true);
    }
}