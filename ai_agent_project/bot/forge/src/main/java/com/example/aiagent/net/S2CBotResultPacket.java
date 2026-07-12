package com.example.aiagent.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server -> Client: a completed step result / observation / episode-end JSON message produced by
 * {@code FakeBotManager} on an integrated singleplayer world. The client relays this verbatim to
 * the Python bridge over its own websocket connection, since only the client owns a bridge socket
 * in that scenario (see {@code IntegratedServerBotHooks}).
 */
public class S2CBotResultPacket {

    private final String json;

    public S2CBotResultPacket(String json) {
        this.json = (json == null) ? "{}" : json;
    }

    public static void encode(S2CBotResultPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.json, 32767);
    }

    public static S2CBotResultPacket decode(FriendlyByteBuf buf) {
        return new S2CBotResultPacket(buf.readUtf(32767));
    }

    public static void handle(S2CBotResultPacket msg, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        ctx.enqueueWork(() -> {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
                com.example.aiagent.client.ForgeWebSocketClient.relayServerResultToBridge(msg.json);
            });
        });
        ctx.setPacketHandled(true);
    }
}
