package com.example.aiagent.server;

import com.example.aiagent.BotMod;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLDedicatedServerSetupEvent;

/**
 * DedicatedServerBootstrap
 *
 * Responsibilities:
 *  - Initialize server-side bot systems at startup
 *  - Drive FakeBotManager every server tick
 */
@Mod.EventBusSubscriber(
        modid = BotMod.MODID,
        value = Dist.DEDICATED_SERVER,
        bus = Mod.EventBusSubscriber.Bus.FORGE   // ✅ THIS IS THE IMPORTANT CHANGE
)
public final class DedicatedServerBootstrap {

    /** Called once when the dedicated server is ready */
    @SubscribeEvent
    public static void onDedicatedServerSetup(FMLDedicatedServerSetupEvent event) {
        event.enqueueWork(() -> {
            new ServerBotHooks();
            System.out.println("[AI-BOT] DedicatedServerBootstrap: ServerBotHooks initialized.");
        });
    }

    /** Called EVERY server tick */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        // Drive FakePlayer physics + DQN actions
        ServerBotHooks.BOTS.tick();
    }
}
