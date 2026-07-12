package com.example.aiagent.net;

import com.example.aiagent.BotMod;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.network.NetworkDirection;

/**
 * Forge network channel for forwarding WS actions to the server.
 * Make sure BotNet.register() is called from common setup.
 */
public final class BotNet {

    private static final String PROTOCOL = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(BotMod.MODID, "net"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals
    );

    private static int id = 0;

    public static void register() {
        CHANNEL.messageBuilder(C2SBotActionPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
            .encoder(C2SBotActionPacket::encode)
            .decoder(C2SBotActionPacket::decode)
            .consumerMainThread(C2SBotActionPacket::handle)
            .add();


        CHANNEL.messageBuilder(S2CBotStatePacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
            .encoder(S2CBotStatePacket::encode)
            .decoder(S2CBotStatePacket::decode)
            .consumerMainThread(S2CBotStatePacket::handle)
            .add();

        CHANNEL.messageBuilder(C2SRuntimeConfigPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
            .encoder(C2SRuntimeConfigPacket::encode)
            .decoder(C2SRuntimeConfigPacket::decode)
            .consumerMainThread(C2SRuntimeConfigPacket::handle)
            .add();

        CHANNEL.messageBuilder(S2CBotResultPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
            .encoder(S2CBotResultPacket::encode)
            .decoder(S2CBotResultPacket::decode)
            .consumerMainThread(S2CBotResultPacket::handle)
            .add();

        System.out.println("[AI-BOT] BotNet registered packets.");

    }

    private BotNet() {}
}
