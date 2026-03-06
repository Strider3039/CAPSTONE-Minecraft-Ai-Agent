package com.example.aiagent.net;

import com.example.aiagent.BotMod;
import com.example.aiagent.server.ServerBridgeWebSocketClient;
import com.google.gson.JsonObject;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client -> Server: runtime config overlay for hot-reload (control_mode, policy.reward, policy.dqn).
 * Server forwards to the bridge via ServerBridgeWebSocketClient.sendConfigUpdate when on dedicated server.
 */
public class C2SRuntimeConfigPacket {

    private final String json;

    public C2SRuntimeConfigPacket(String json) {
        this.json = (json == null) ? "{}" : json;
    }

    public static void encode(C2SRuntimeConfigPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.json, 32767);
    }

    public static C2SRuntimeConfigPacket decode(FriendlyByteBuf buf) {
        return new C2SRuntimeConfigPacket(buf.readUtf(32767));
    }

    public static void handle(C2SRuntimeConfigPacket msg, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        ctx.enqueueWork(() -> {
            try {
                JsonObject payload = BotMod.GSON.fromJson(msg.json, JsonObject.class);
                if (payload == null) return;
                ServerBridgeWebSocketClient bridge = BotMod.getInstance().getBridgeClient();
                if (bridge != null) {
                    bridge.sendConfigUpdate(payload);
                }
            } catch (Exception e) {
                System.err.println("[AI-BOT] C2SRuntimeConfigPacket handle failed: " + e.getMessage());
            }
        });
        ctx.setPacketHandled(true);
    }
}
