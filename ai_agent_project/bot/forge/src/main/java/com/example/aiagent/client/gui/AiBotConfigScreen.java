package com.example.aiagent.client.gui;

import com.example.aiagent.BotMod;
import com.example.aiagent.client.ForgeWebSocketClient;
import com.example.aiagent.net.BotNet;
import com.example.aiagent.net.C2SRuntimeConfigPacket;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

public class AiBotConfigScreen extends Screen {

    private final Screen parent;

    private Mode selectedMode = Mode.SERVER_BOT;

    /** Clamped: min 0, max 1. Updated by slider and when Apply is pressed sent to bridge. */
    private double epsilonValue = 1.0;
    /** Clamped: min 0, max 0.01. Updated by slider and when Apply is pressed sent to bridge. */
    private double survivalRewardValue = 0.001;

    private static final double EPSILON_MIN = 0.0;
    private static final double EPSILON_MAX = 1.0;
    private static final double SURVIVAL_MIN = 0.0;
    private static final double SURVIVAL_MAX = 0.01;

    public enum Mode {
        SERVER_BOT("SERVER_BOT"),
        PLAYER("PLAYER");

        public final String label;
        Mode(String label) { this.label = label; }

        @Override
        public String toString() { return label; }
    }

    public AiBotConfigScreen(Screen parent) {
        super(Component.literal("AI Agent Bot Settings"));
        this.parent = parent;
    }

    /** Build runtime overlay payload for hot-reload (control_mode, policy.dqn.epsilon_start, policy.reward.survival_reward). */
    private JsonObject buildConfigPayload() {
        JsonObject payload = new JsonObject();
        payload.addProperty("control_mode", this.selectedMode.label);
        JsonObject policy = new JsonObject();
        JsonObject dqn = new JsonObject();
        dqn.addProperty("epsilon_start", Mth.clamp(epsilonValue, EPSILON_MIN, EPSILON_MAX));
        policy.add("dqn", dqn);
        JsonObject reward = new JsonObject();
        reward.addProperty("survival_reward", Mth.clamp(survivalRewardValue, SURVIVAL_MIN, SURVIVAL_MAX));
        policy.add("reward", reward);
        payload.add("policy", policy);
        return payload;
    }

    @Override
    protected void init() {
        final int centerX = this.width / 2;
        final int startY = 60;
        final int rowH = 24;
        final int btnW = 200;
        final int btnH = 20;
        final int sliderW = 180;

        // Sync selected mode with current control mode when opening the screen
        this.selectedMode = ForgeWebSocketClient.getControlMode() == ForgeWebSocketClient.ControlMode.SERVER_BOT
            ? Mode.SERVER_BOT
            : Mode.PLAYER;

        // Mode cycle button (looks like vanilla option toggles)
        this.addRenderableWidget(
            CycleButton.<Mode>builder(m -> Component.literal(m.label))
                .withValues(Mode.values())
                .withInitialValue(this.selectedMode)
                .create(centerX - btnW / 2, startY, btnW, btnH,
                    Component.literal("Mode"),
                    (btn, value) -> this.selectedMode = value)
        );

        // Epsilon slider (0 = left, 1 = right); value 0..1
        double epsilonNorm = (epsilonValue - EPSILON_MIN) / (EPSILON_MAX - EPSILON_MIN);
        this.addRenderableWidget(new AbstractSliderButton(
                centerX - btnW / 2, startY + rowH,
                sliderW, btnH,
                Component.literal("Epsilon: " + String.format("%.2f", epsilonValue)),
                Mth.clamp(epsilonNorm, 0.0, 1.0)
        ) {
            @Override
            protected void updateMessage() {
                double v = Mth.lerp(this.value, EPSILON_MIN, EPSILON_MAX);
                epsilonValue = Mth.clamp(v, EPSILON_MIN, EPSILON_MAX);
                setMessage(Component.literal("Epsilon: " + String.format("%.2f", epsilonValue)));
            }
            @Override
            protected void applyValue() {
                double v = Mth.lerp(this.value, EPSILON_MIN, EPSILON_MAX);
                epsilonValue = Mth.clamp(v, EPSILON_MIN, EPSILON_MAX);
            }
        });

        // Survival reward slider (0 = left, 0.01 = right)
        double survivalNorm = (survivalRewardValue - SURVIVAL_MIN) / (SURVIVAL_MAX - SURVIVAL_MIN);
        this.addRenderableWidget(new AbstractSliderButton(
                centerX - btnW / 2, startY + rowH * 2,
                sliderW, btnH,
                Component.literal("Survival: " + String.format("%.4f", survivalRewardValue)),
                Mth.clamp(survivalNorm, 0.0, 1.0)
        ) {
            @Override
            protected void updateMessage() {
                double v = Mth.lerp(this.value, SURVIVAL_MIN, SURVIVAL_MAX);
                survivalRewardValue = Mth.clamp(v, SURVIVAL_MIN, SURVIVAL_MAX);
                setMessage(Component.literal("Survival: " + String.format("%.4f", survivalRewardValue)));
            }
            @Override
            protected void applyValue() {
                double v = Mth.lerp(this.value, SURVIVAL_MIN, SURVIVAL_MAX);
                survivalRewardValue = Mth.clamp(v, SURVIVAL_MIN, SURVIVAL_MAX);
            }
        });

        // Apply: sync mode + slider values to client and bridge
        this.addRenderableWidget(
            Button.builder(Component.literal("Apply"), btn -> {
                ForgeWebSocketClient.ControlMode ctrl = this.selectedMode == Mode.SERVER_BOT
                    ? ForgeWebSocketClient.ControlMode.SERVER_BOT
                    : ForgeWebSocketClient.ControlMode.PLAYER;
                ForgeWebSocketClient.setControlMode(ctrl);

                JsonObject payload = buildConfigPayload();
                ForgeWebSocketClient.sendConfigUpdate(payload);
                if (this.minecraft != null && this.minecraft.getConnection() != null) {
                    BotNet.CHANNEL.sendToServer(new C2SRuntimeConfigPacket(BotMod.GSON.toJson(payload)));
                }

                btn.setMessage(Component.literal("Applied"));
            }).bounds(centerX - btnW / 2, startY + rowH * 3, btnW, btnH).build()
        );

        // Done
        this.addRenderableWidget(
            Button.builder(Component.literal("Done"), btn -> this.onClose())
                .bounds(centerX - btnW / 2, startY + rowH * 4, btnW, btnH)
                .build()
        );
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Vanilla-style: dim background + centered title
        this.renderBackground(guiGraphics);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 20, 0xFFFFFF);

        // Optional: small subtitle / hint
        guiGraphics.drawCenteredString(
            this.font,
            Component.literal("Configure agent mode and training parameters"),
            this.width / 2,
            35,
            0xA0A0A0
        );

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }
}