package com.example.aiagent;

import com.example.aiagent.net.BotNet;
import com.example.aiagent.server.ServerBotHooks;
import com.example.aiagent.server.ServerBridgeWebSocketClient;
import com.example.aiagent.server.FakeBotManager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.api.distmarker.Dist;

@Mod(BotMod.MODID)
public class BotMod {

    public static final String MODID = "ai_agent_bot";
    public static final Gson GSON = new GsonBuilder().create();

    private static BotMod INSTANCE;
    public static BotMod getInstance() { return INSTANCE; }

    // Episode state (kept common)
    private long episodeStartTick = 0L;
    private boolean episodeActive = false;

    private FakeBotManager botManager;
    /** Set on dedicated server when ServerBotHooks is created; used to forward config_update to the bridge. */
    private ServerBridgeWebSocketClient bridgeClient;

    public FakeBotManager getBotManager() { return botManager; }
    public void setBridgeClient(ServerBridgeWebSocketClient c) { this.bridgeClient = c; }
    public ServerBridgeWebSocketClient getBridgeClient() { return bridgeClient; }
    public long getEpisodeStartTick() { return episodeStartTick; }
    public boolean isEpisodeActive() { return episodeActive; }

    public BotMod() {
        INSTANCE = this;

        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(this::onCommonSetup);

        if (FMLEnvironment.dist == Dist.CLIENT) {
            try {
                Class.forName("com.example.aiagent.client.ClientConfigRegistration")
                    .getMethod("register")
                    .invoke(null);
            } catch (Exception e) {
                throw new RuntimeException("Failed to register client config screen", e);
            }
        }

        System.out.println("[AI-BOT] BotMod constructed (common).");
    }

    private void onCommonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(BotNet::register);
        System.out.println("[AI-BOT] CommonSetup: BotNet.register enqueued.");

        if (FMLEnvironment.dist == Dist.DEDICATED_SERVER) {
            event.enqueueWork(() -> {
                System.out.println("[AI-BOT] CommonSetup: constructing FakeBotManager + ServerBotHooks (dedicated server).");
                this.botManager = new FakeBotManager();
                new ServerBotHooks(this.botManager);
            });
        }

    }

    public void markEpisodeStarted(long startTick) {
        this.episodeStartTick = startTick;
        this.episodeActive = true;
        System.out.println("[AI-BOT] Episode started. tick=" + startTick);
    }

    public void markEpisodeEnded(String reason) {
        this.episodeActive = false;
        System.out.println("[AI-BOT] Episode ended. reason=" + reason);
    }
}
