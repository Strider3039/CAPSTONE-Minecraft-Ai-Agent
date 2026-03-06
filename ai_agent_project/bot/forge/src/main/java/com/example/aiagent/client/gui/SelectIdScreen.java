package com.example.aiagent.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * A screen that shows a searchable list of string IDs (e.g. block or entity type IDs).
 * User can type to filter and click an entry to select it; callback is invoked and screen closes.
 */
public class SelectIdScreen extends Screen {

    private final Screen parent;
    private final Consumer<String> onSelect;
    private final List<String> allIds;
    private EditBox searchBox;
    private final List<String> filteredIds = new ArrayList<>();
    private static final int LIST_TOP = 56;
    private static final int ROW_H = 22;
    private int listWidth = 280;

    public SelectIdScreen(Screen parent, String titleKey, Stream<String> idStream, Consumer<String> onSelect) {
        super(Component.literal(titleKey));
        this.parent = parent;
        this.onSelect = onSelect;
        this.allIds = idStream.sorted().toList();
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        listWidth = Math.min(320, this.width - 80);
        int searchW = listWidth;

        this.searchBox = new EditBox(this.font, centerX - searchW / 2, 40, searchW, 20, Component.literal("Search"));
        this.searchBox.setHint(Component.literal("Type to filter..."));
        this.searchBox.setResponder(s -> refilter());
        this.addRenderableWidget(this.searchBox);
        this.setInitialFocus(this.searchBox);

        refilter();

        this.addRenderableWidget(
            Button.builder(Component.literal("Cancel"), btn -> {
                if (this.minecraft != null) this.minecraft.setScreen(parent);
            }).bounds(centerX - 60, this.height - 28, 120, 20).build()
        );
    }

    private void refilter() {
        String q = this.searchBox == null ? "" : this.searchBox.getValue().trim().toLowerCase(Locale.ROOT);
        this.filteredIds.clear();
        for (String id : this.allIds) {
            if (q.isEmpty() || id.toLowerCase(Locale.ROOT).contains(q)) {
                this.filteredIds.add(id);
            }
        }
    }

    private void select(String id) {
        if (onSelect != null) {
            onSelect.accept(id);
            // Callback is responsible for setting the new screen (e.g. refreshed config screen with the new entry)
        } else if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 18, 0xFFFFFF);
        this.searchBox.render(guiGraphics, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;
        int left = centerX - listWidth / 2;
        int listBottom = this.height - 40;
        int maxRows = (listBottom - LIST_TOP) / ROW_H;
        List<String> toShow = filteredIds.size() > maxRows ? filteredIds.subList(0, maxRows) : filteredIds;
        for (int i = 0; i < toShow.size(); i++) {
            String id = toShow.get(i);
            int y = LIST_TOP + i * ROW_H;
            boolean hover = mouseX >= left && mouseX < left + listWidth && mouseY >= y && mouseY < y + ROW_H;
            guiGraphics.fill(left, y, left + listWidth, y + ROW_H - 1, hover ? 0x40FFFFFF : 0x20FFFFFF);
            guiGraphics.drawString(this.font, id, left + 4, y + (ROW_H - this.font.lineHeight) / 2, 0xE0E0E0, false);
        }

        super.render(guiGraphics, mouseX, mouseY, partialTick);
        if (filteredIds.size() > maxRows) {
            guiGraphics.drawCenteredString(this.font, "(" + maxRows + " of " + filteredIds.size() + " - narrow search)", this.width / 2, listBottom - 12, 0xA0A0A0);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int centerX = this.width / 2;
            int left = centerX - listWidth / 2;
            int listBottom = this.height - 40;
            int maxRows = (listBottom - LIST_TOP) / ROW_H;
            List<String> toShow = filteredIds.size() > maxRows ? filteredIds.subList(0, maxRows) : filteredIds;
            for (int i = 0; i < toShow.size(); i++) {
                int y = LIST_TOP + i * ROW_H;
                if (mouseX >= left && mouseX < left + listWidth && mouseY >= y && mouseY < y + ROW_H) {
                    select(toShow.get(i));
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
