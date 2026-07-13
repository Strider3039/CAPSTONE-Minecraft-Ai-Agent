package com.example.aiagent.server;

import com.example.aiagent.net.BotNet;
import com.example.aiagent.net.S2CBotResultPacket;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.PacketDistributor;

/*
   IntegratedServerBotHooks (FOR INTEGRATED SINGLEPLAYER)
 */
public class IntegratedServerBotHooks {

    private final FakeBotManager bots;
    private boolean spawned = false;

    public IntegratedServerBotHooks(FakeBotManager bots) {
        this.bots = bots;
        MinecraftForge.EVENT_BUS.register(this);
        System.out.println("[AI-BOT] IntegratedServerBotHooks registered (integrated singleplayer path).");
    }

    @SubscribeEvent
    public void onLevelLoad(LevelEvent.Load event) {
        if (spawned) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        MinecraftServer server = level.getServer();
        if (server == null) return;

        if (!level.dimension().location().toString().equals("minecraft:overworld")) return;

        System.out.println("[AI-BOT] Integrated server overworld loaded. Spawning FakePlayer bot...");
        bots.ensureDefaultBot(server, level);
        spawned = true;
    }

    @SubscribeEvent
    public void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        if (!spawned) return;
        if (!(event.level instanceof ServerLevel level)) return;
        if (!level.dimension().location().toString().equals("minecraft:overworld")) return;

        // Apply queued actions (forwarded from the client via C2SBotActionPacket) + physics.
        bots.tick();

        // Relay completed step results/observations back to the client, which forwards them to
        // Python over the client's existing bridge websocket (there is no server-owned socket here).
        JsonObject msg;
        while ((msg = bots.pollCompletedResult()) != null) {
            BotNet.CHANNEL.send(PacketDistributor.ALL.noArg(), new S2CBotResultPacket(msg.toString()));
        }
    }
}
