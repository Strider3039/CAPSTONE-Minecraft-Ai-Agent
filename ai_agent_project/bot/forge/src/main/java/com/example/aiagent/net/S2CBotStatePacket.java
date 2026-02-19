package com.example.aiagent.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;
import java.util.UUID;

public record S2CBotStatePacket(
        String botId,
        int entityId,
        UUID uuid,
        long serverTick,
        double x, double y, double z,
        double vx, double vy, double vz,
        float headYaw, float bodyYaw, float pitch,
        boolean onGround,
        boolean swingMainHandPulse
) {
    public static void encode(S2CBotStatePacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.botId, 64);
        buf.writeInt(msg.entityId);
        buf.writeUUID(msg.uuid);
        buf.writeLong(msg.serverTick);

        buf.writeDouble(msg.x);
        buf.writeDouble(msg.y);
        buf.writeDouble(msg.z);

        buf.writeDouble(msg.vx);
        buf.writeDouble(msg.vy);
        buf.writeDouble(msg.vz);

        buf.writeFloat(msg.headYaw);
        buf.writeFloat(msg.bodyYaw);
        buf.writeFloat(msg.pitch);

        buf.writeBoolean(msg.onGround);
        buf.writeBoolean(msg.swingMainHandPulse);
    }


    public static S2CBotStatePacket decode(FriendlyByteBuf buf) {
        String botId = buf.readUtf(64);
        int entityId = buf.readInt();
        UUID uuid = buf.readUUID();
        long serverTick = buf.readLong();

        double x = buf.readDouble();
        double y = buf.readDouble();
        double z = buf.readDouble();

        double vx = buf.readDouble();
        double vy = buf.readDouble();
        double vz = buf.readDouble();

        float headYaw = buf.readFloat();
        float bodyYaw = buf.readFloat();
        float pitch = buf.readFloat();

        boolean onGround = buf.readBoolean();
        boolean swingMainHandPulse = buf.readBoolean();

        return new S2CBotStatePacket(
                botId,
                entityId,
                uuid,
                serverTick,
                x, y, z,
                vx, vy, vz,
                headYaw, bodyYaw, pitch,
                onGround,
                swingMainHandPulse
        );
    }

    public static void handle(S2CBotStatePacket msg, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        ctx.enqueueWork(() -> {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
                System.out.println("[AI-BOT][DBG][CL-RECV] bot=" + msg.botId()
                    + " serverTick=" + msg.serverTick()
                    + " hash=" + System.identityHashCode(msg)
                    + " thread=" + Thread.currentThread().getName());
                com.example.aiagent.client.ClientGhostBots.onBotState(msg);
            });
        });
        ctx.setPacketHandled(true);
    }
}