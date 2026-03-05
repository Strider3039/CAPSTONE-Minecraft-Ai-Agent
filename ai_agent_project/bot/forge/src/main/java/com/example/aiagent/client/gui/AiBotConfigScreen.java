package com.example.aiagent.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class AiBotConfigScreen extends Screen {

    private final Screen parent;

    // Placeholder until we wire packets/settings
    private Mode selectedMode = Mode.SERVER_BOT;

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

    @Override
    protected void init() {
        final int centerX = this.width / 2;
        final int startY = 60;
        final int rowH = 24;
        final int btnW = 200;
        final int btnH = 20;

        // Mode cycle button (looks like vanilla option toggles)
        this.addRenderableWidget(
            CycleButton.<Mode>builder(m -> Component.literal(m.label))
                .withValues(Mode.values())
                .withInitialValue(this.selectedMode)
                .create(centerX - btnW / 2, startY, btnW, btnH,
                    Component.literal("Mode"),
                    (btn, value) -> this.selectedMode = value)
        );

        // Apply (no-op for now)
        this.addRenderableWidget(
            Button.builder(Component.literal("Apply (coming soon)"), btn -> {
                // Next step: send packet to server + persist config
                btn.active = false;
                btn.setMessage(Component.literal("Apply (coming soon)"));
            }).bounds(centerX - btnW / 2, startY + rowH * 2, btnW, btnH).build()
        );

        // Done
        this.addRenderableWidget(
            Button.builder(Component.literal("Done"), btn -> this.onClose())
                .bounds(centerX - btnW / 2, startY + rowH * 3, btnW, btnH)
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