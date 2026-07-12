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

/**
 * IntegratedServerBotHooks (INTEGRATED SINGLEPLAYER)
 *
 * On a real dedicated server, {@link ServerBotHooks} owns spawning/ticking {@link FakeBotManager}
 * and the server-role bridge websocket. On an integrated singleplayer world (launched via
 * runClient, i.e. a "localhost" world), {@code FMLEnvironment.dist == CLIENT}, so
 * {@code ServerBotHooks} is never constructed (see {@code BotMod.onCommonSetup}) -- the client
 * instead owns the only bridge websocket connection and forwards SERVER_BOT actions to the server
 * via {@code C2SBotActionPacket}.
 *
 * Without this class, those forwarded actions were enqueued into {@link FakeBotManager} but never
 * drained/applied, because {@link FakeBotManager#tick()} was only ever called from
 * {@code ServerBotHooks}. This class is the integrated-SP counterpart: it spawns the default bot
 * and ticks {@link FakeBotManager} every server tick so forwarded actions actually move the
 * FakePlayer, and relays completed step results/observations back to the client (which forwards
 * them to the Python bridge) via {@link S2CBotResultPacket}.
 *
 * It deliberately does NOT open its own bridge websocket -- that would fight the client's
 * connection (see the dedicated-server-only gate in {@code BotMod}).
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
