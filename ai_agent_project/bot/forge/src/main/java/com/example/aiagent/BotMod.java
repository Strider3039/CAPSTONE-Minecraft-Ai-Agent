package com.example.aiagent;

import com.example.aiagent.server.ServerBotHooks;
import net.minecraftforge.common.MinecraftForge;

import com.example.aiagent.net.BotNet;
import com.example.aiagent.server.FakeBotManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(BotMod.MODID)
public class BotMod {

    public static final String MODID = "ai_agent_bot";
    public static final Gson GSON = new GsonBuilder().create();

    private static BotMod INSTANCE;
    public static BotMod getInstance() { return INSTANCE; }

    // Episode state (kept common)
    private long episodeStartTick = 0L;
    private boolean episodeActive = false;

    public long getEpisodeStartTick() { return episodeStartTick; }
    public boolean isEpisodeActive() { return episodeActive; }

    public BotMod() {
        INSTANCE = this;

        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(this::onCommonSetup);

        MinecraftForge.EVENT_BUS.register(new ServerBotHooks());

        System.out.println("[AI-BOT] BotMod constructed (common).");
    }

    private void onCommonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(BotNet::register);
        System.out.println("[AI-BOT] CommonSetup: BotNet.register enqueued.");
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
