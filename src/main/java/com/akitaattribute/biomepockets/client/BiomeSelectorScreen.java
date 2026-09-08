package com.akitaattribute.biomepockets.client;

import com.akitaattribute.biomepockets.network.NetworkHandler;
import com.akitaattribute.biomepockets.network.SelectBiomePacket;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class BiomeSelectorScreen extends Screen {
    private static final int PANEL_WIDTH = 320;
    private static final int ROW_HEIGHT = 28;
    private static final int LIST_TOP = 72;
    private static final int LIST_BOTTOM_MARGIN = 24;

    private final List<ResourceLocation> allBiomes;
    private List<ResourceLocation> filteredBiomes;
    private EditBox searchBox;
    private int scrollOffset;

    public BiomeSelectorScreen(List<ResourceLocation> biomes) {
        super(new TextComponent("Biome Transporter Selector"));
        this.allBiomes = new ArrayList<>(biomes);
        this.filteredBiomes = new ArrayList<>(biomes);
    }

    @Override
    protected void init() {
        int left = (this.width - PANEL_WIDTH) / 2;
        this.searchBox = new EditBox(this.font, left + 12, 42, PANEL_WIDTH - 24, 20, new TextComponent("Search biomes"));
        this.searchBox.setMaxLength(100);
        this.searchBox.setResponder(this::applyFilter);
        this.addRenderableWidget(this.searchBox);
    }

    private void applyFilter(String query) {
        String needle = query.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            this.filteredBiomes = new ArrayList<>(this.allBiomes);
        } else {
            this.filteredBiomes = this.allBiomes.stream()
                    .filter(id -> id.toString().toLowerCase(Locale.ROOT).contains(needle)
                            || friendlyName(id).toLowerCase(Locale.ROOT).contains(needle))
                    .toList();
        }
        this.scrollOffset = 0;
    }

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(poseStack);
        int left = (this.width - PANEL_WIDTH) / 2;
        int right = left + PANEL_WIDTH;
        int bottom = this.height - LIST_BOTTOM_MARGIN;

        fill(poseStack, left, 26, right, bottom + 6, 0xCC161616);
        fill(poseStack, left + 1, 27, right - 1, 29, 0xFF6F6F6F);
        drawCenteredString(poseStack, this.font, this.title, this.width / 2, 30, 0xFFFFFF);

        int visibleRows = Math.max(1, (bottom - LIST_TOP) / ROW_HEIGHT);
        int end = Math.min(this.filteredBiomes.size(), this.scrollOffset + visibleRows);
        for (int index = this.scrollOffset; index < end; index++) {
            int row = index - this.scrollOffset;
            int y = LIST_TOP + row * ROW_HEIGHT;
            ResourceLocation biome = this.filteredBiomes.get(index);
            boolean hovered = mouseX >= left + 8 && mouseX < right - 8 && mouseY >= y && mouseY < y + ROW_HEIGHT - 2;
            fill(poseStack, left + 8, y, right - 8, y + ROW_HEIGHT - 2, hovered ? 0xAA4B4B4B : 0xAA292929);
            this.font.draw(poseStack, friendlyName(biome), left + 14, y + 5, 0xFFFFFF);
            this.font.draw(poseStack, biome.toString(), left + 14, y + 16, 0xFFAAAAAA);
        }

        if (this.filteredBiomes.isEmpty()) {
            drawCenteredString(poseStack, this.font, new TextComponent("No matching biomes"), this.width / 2, LIST_TOP + 12, 0xFFAAAAAA);
        }

        this.font.draw(poseStack, "Search", left + 12, 33, 0xFFBBBBBB);
        super.render(poseStack, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button != 0) {
            return false;
        }

        int left = (this.width - PANEL_WIDTH) / 2;
        int right = left + PANEL_WIDTH;
        int bottom = this.height - LIST_BOTTOM_MARGIN;
        if (mouseX < left + 8 || mouseX >= right - 8 || mouseY < LIST_TOP || mouseY >= bottom) {
            return false;
        }

        int row = (int) ((mouseY - LIST_TOP) / ROW_HEIGHT);
        int index = this.scrollOffset + row;
        if (index >= 0 && index < this.filteredBiomes.size()) {
            NetworkHandler.CHANNEL.sendToServer(new SelectBiomePacket(this.filteredBiomes.get(index)));
            this.onClose();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int visibleRows = Math.max(1, (this.height - LIST_BOTTOM_MARGIN - LIST_TOP) / ROW_HEIGHT);
        int maxOffset = Math.max(0, this.filteredBiomes.size() - visibleRows);
        if (delta != 0.0D) {
            this.scrollOffset = Mth.clamp(this.scrollOffset - (int) Math.signum(delta), 0, maxOffset);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static String friendlyName(ResourceLocation id) {
        String[] words = id.getPath().replace('-', '_').split("_");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (result.length() > 0) {
                result.append(' ');
            }
            result.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) {
                result.append(word.substring(1));
            }
        }
        return result + " [" + id.getNamespace() + "]";
    }
}
