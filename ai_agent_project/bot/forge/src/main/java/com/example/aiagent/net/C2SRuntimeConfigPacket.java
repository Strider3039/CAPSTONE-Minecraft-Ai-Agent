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
                if (payload == null) {
                    System.out.println("[AI-BOT][DEBUG][C2S] runtime config SKIP: parsed payload is null jsonLen="
                            + (msg.json == null ? 0 : msg.json.length()));
                    return;
                }
                ServerBridgeWebSocketClient bridge = BotMod.getInstance().getBridgeClient();
                if (bridge == null) {
                    System.out.println("[AI-BOT][DEBUG][C2S] runtime config SKIP: getBridgeClient() is null (not dedicated / hooks not registered?)");
                    return;
                }

                String mode = null;
                if (payload.has("control_mode") && payload.get("control_mode").isJsonPrimitive()) {
                    mode = payload.get("control_mode").getAsString().trim().replace("-", "_").toUpperCase();
                }

                System.out.println("[AI-BOT][DEBUG][C2S] runtime config recv control_mode=" + mode
                        + " keys=" + payload.keySet()
                        + " bridgeAutoConnect(before)=" + bridge.isAutoConnectEnabled()
                        + " bridgeOpen=" + bridge.isConnected());

                if ("SERVER_BOT".equals(mode)) {
                    bridge.setAutoConnect(true);
                }

                bridge.sendConfigUpdate(payload);

                // After Python sees PLAYER, stop reconnecting so the game client can own the bridge.
                if ("PLAYER".equals(mode)) {
                    bridge.pauseForPlayerMode();
                }
            } catch (Exception e) {
                System.err.println("[AI-BOT] C2SRuntimeConfigPacket handle failed: " + e.getMessage());
            }
        });
        ctx.setPacketHandled(true);
    }
}
