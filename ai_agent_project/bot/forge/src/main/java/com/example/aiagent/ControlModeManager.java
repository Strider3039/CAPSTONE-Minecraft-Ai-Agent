package com.example.aiagent;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Global toggle for who receives DQN actions:
 *  - PLAYER: apply inputs to LocalPlayer on client (normal play)
 *  - FAKE:   forward actions to server to control FakePlayer/Avatar
 */
public final class ControlModeManager {

    public enum Mode {
        PLAYER,
        FAKE
    }

    private static volatile Mode mode = Mode.PLAYER;

    public static Mode get() {
        return mode;
    }

    public static void set(Mode newMode) {
        mode = (newMode == null) ? Mode.PLAYER : newMode;

        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            mc.execute(() -> {
                if (mc.player != null) {
                    mc.player.displayClientMessage(
                            Component.literal("§b[AI] Control Mode: §f" + mode.name()),
                            true
                    );
                }
            });
        }
        System.out.println("[AI] ControlMode -> " + mode);
    }

    public static void toggle() {
        set(mode == Mode.PLAYER ? Mode.FAKE : Mode.PLAYER);
    }

    private ControlModeManager() {}
}
