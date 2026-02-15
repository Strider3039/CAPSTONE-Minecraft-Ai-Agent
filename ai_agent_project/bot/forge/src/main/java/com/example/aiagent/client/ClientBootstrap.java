package com.example.aiagent.client;

import com.example.aiagent.BotMod;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(modid = BotMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ClientBootstrap {

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        // Runs ONLY on client. Safe. No DistExecutor needed.
        event.enqueueWork(ClientBridgeHooks::init);
        System.out.println("[AI-BOT] ClientBootstrap: ClientBridgeHooks.init enqueued.");
        event.enqueueWork(() -> {
            MinecraftForge.EVENT_BUS.addListener(ClientGhostBots::onClientTick);
        });
    }
}
