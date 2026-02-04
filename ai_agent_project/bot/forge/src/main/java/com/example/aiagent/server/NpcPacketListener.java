package com.example.aiagent.server;

import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

public class NpcPacketListener extends ServerGamePacketListenerImpl {

    public NpcPacketListener(
            MinecraftServer server,
            Connection connection,
            ServerPlayer player
    ) {
        super(server, connection, player);
    }
}
