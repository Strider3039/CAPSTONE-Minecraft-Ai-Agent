package com.example.aiagent.client;

import com.example.aiagent.client.gui.AiBotConfigScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.fml.ModLoadingContext;

/**
 * Client-only config screen registration. Referenced only via reflection from BotMod
 * so that the server never loads Screen or AiBotConfigScreen.
 */
public final class ClientConfigRegistration {

    public static void register() {
        ModLoadingContext.get().registerExtensionPoint(
            ConfigScreenHandler.ConfigScreenFactory.class,
            () -> new ConfigScreenHandler.ConfigScreenFactory(
                (Minecraft mc, Screen parent) -> new AiBotConfigScreen(parent)
            )
        );
    }
}
