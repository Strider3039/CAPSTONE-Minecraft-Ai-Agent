package com.example.aiagent.net;

import com.example.aiagent.server.NpcBotController;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client -> Server: forward an action payload (JSON string)
 * Server applies it to the REAL NPC player (NpcBotController).
 */
public class C2SBotActionPacket {

    private final String json;

    public C2SBotActionPacket(String json) {
        this.json = (json == null) ? "{}" : json;
    }

    public static void encode(C2SBotActionPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.json, 32767);
    }

    public static C2SBotActionPacket decode(FriendlyByteBuf buf) {
        return new C2SBotActionPacket(buf.readUtf(32767));
    }

    public static void handle(C2SBotActionPacket msg, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        ctx.enqueueWork(() -> {
            try {
                NpcBotController.enqueueActionJson(msg.json);
            } catch (Exception e) {
                System.err.println("[AI-BOT] C2SBotActionPacket handle failed: " + e.getMessage());
            }
        });
        ctx.setPacketHandled(true);
    }
}
