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
    private static final int PANEL_WIDTH = 360;
    private static final int ROW_HEIGHT = 28;
    private static final int TABS_TOP = 66;
    private static final int TAB_HEIGHT = 20;
    private static final int LIST_TOP = 92;
    private static final int LIST_BOTTOM_MARGIN = 24;

    private final List<ResourceLocation> allBiomes;
    private final List<String> allNamespaces;
    private List<ResourceLocation> filteredBiomes;
    private List<String> filteredNamespaces;
    private EditBox searchBox;
    private ViewMode viewMode = ViewMode.BIOMES;
    private String selectedNamespace;
    private int scrollOffset;

    public BiomeSelectorScreen(List<ResourceLocation> biomes) {
        super(new TextComponent("Biome Transporter Selector"));
        this.allBiomes = new ArrayList<>(biomes);
        this.filteredBiomes = new ArrayList<>(biomes);
        this.allNamespaces = biomes.stream()
                .map(ResourceLocation::getNamespace)
                .distinct()
                .sorted()
                .toList();
        this.filteredNamespaces = new ArrayList<>(this.allNamespaces);
    }

    @Override
    protected void init() {
        int left = (this.width - PANEL_WIDTH) / 2;
        this.searchBox = new EditBox(this.font, left + 12, 42, PANEL_WIDTH - 24, 20, new TextComponent("Search biomes"));
        this.searchBox.setMaxLength(100);
        this.searchBox.setResponder(this::applyFilter);
        this.addRenderableWidget(this.searchBox);
        applyFilter(this.searchBox.getValue());
    }

    private void applyFilter(String query) {
        String needle = query.trim().toLowerCase(Locale.ROOT);

        if (needle.startsWith("#")) {
            String namespaceNeedle = needle.substring(1);
            this.filteredBiomes = this.allBiomes.stream()
                    .filter(id -> id.getNamespace().toLowerCase(Locale.ROOT).contains(namespaceNeedle))
                    .toList();
            this.filteredNamespaces = List.of();
            this.scrollOffset = 0;
            return;
        }

        if (this.viewMode == ViewMode.MODS && this.selectedNamespace == null) {
            this.filteredNamespaces = this.allNamespaces.stream()
                    .filter(namespace -> needle.isEmpty()
                            || namespace.toLowerCase(Locale.ROOT).contains(needle)
                            || friendlyNamespace(namespace).toLowerCase(Locale.ROOT).contains(needle))
                    .toList();
            this.filteredBiomes = List.of();
        } else {
            this.filteredBiomes = this.allBiomes.stream()
                    .filter(id -> this.selectedNamespace == null || id.getNamespace().equals(this.selectedNamespace))
                    .filter(id -> needle.isEmpty()
                            || id.toString().toLowerCase(Locale.ROOT).contains(needle)
                            || friendlyName(id).toLowerCase(Locale.ROOT).contains(needle))
                    .toList();
            this.filteredNamespaces = List.of();
        }
        this.scrollOffset = 0;
    }

    private boolean showingBiomeRows() {
        String query = this.searchBox == null ? "" : this.searchBox.getValue().trim();
        return query.startsWith("#") || this.viewMode == ViewMode.BIOMES || this.selectedNamespace != null;
    }

    private void selectView(ViewMode mode) {
        if (mode == ViewMode.MODS && this.viewMode == ViewMode.MODS && this.selectedNamespace != null) {
            this.selectedNamespace = null;
        } else {
            this.viewMode = mode;
            this.selectedNamespace = null;
        }
        applyFilter(this.searchBox == null ? "" : this.searchBox.getValue());
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

        renderTabs(poseStack, left);

        int rowCount = showingBiomeRows() ? this.filteredBiomes.size() : this.filteredNamespaces.size();
        int visibleRows = Math.max(1, (bottom - LIST_TOP) / ROW_HEIGHT);
        int end = Math.min(rowCount, this.scrollOffset + visibleRows);
        for (int index = this.scrollOffset; index < end; index++) {
            int row = index - this.scrollOffset;
            int y = LIST_TOP + row * ROW_HEIGHT;
            boolean hovered = mouseX >= left + 8 && mouseX < right - 8 && mouseY >= y && mouseY < y + ROW_HEIGHT - 2;
            fill(poseStack, left + 8, y, right - 8, y + ROW_HEIGHT - 2, hovered ? 0xAA4B4B4B : 0xAA292929);

            if (showingBiomeRows()) {
                ResourceLocation biome = this.filteredBiomes.get(index);
                this.font.draw(poseStack, friendlyName(biome), left + 14, y + 5, 0xFFFFFF);
                this.font.draw(poseStack, biome.toString(), left + 14, y + 16, 0xFFAAAAAA);
            } else {
                String namespace = this.filteredNamespaces.get(index);
                long count = this.allBiomes.stream().filter(id -> id.getNamespace().equals(namespace)).count();
                this.font.draw(poseStack, friendlyNamespace(namespace), left + 14, y + 5, 0xFFFFFF);
                this.font.draw(poseStack, "#" + namespace + "  -  " + count + " biomes", left + 14, y + 16, 0xFFAAAAAA);
            }
        }

        if (rowCount == 0) {
            drawCenteredString(poseStack, this.font, new TextComponent("No matching entries"), this.width / 2, LIST_TOP + 12, 0xFFAAAAAA);
        }

        this.font.draw(poseStack, "Search", left + 12, 33, 0xFFBBBBBB);
        super.render(poseStack, mouseX, mouseY, partialTick);
    }

    private void renderTabs(PoseStack poseStack, int left) {
        int innerWidth = PANEL_WIDTH - 24;
        int tabWidth = innerWidth / 2;
        int firstLeft = left + 12;
        int secondLeft = firstLeft + tabWidth;

        fill(poseStack, firstLeft, TABS_TOP, secondLeft - 1, TABS_TOP + TAB_HEIGHT,
                this.viewMode == ViewMode.BIOMES ? 0xFF4B4B4B : 0xFF292929);
        fill(poseStack, secondLeft + 1, TABS_TOP, firstLeft + innerWidth, TABS_TOP + TAB_HEIGHT,
                this.viewMode == ViewMode.MODS ? 0xFF4B4B4B : 0xFF292929);

        drawCenteredString(poseStack, this.font, new TextComponent("Biomes"),
                firstLeft + tabWidth / 2, TABS_TOP + 6, 0xFFFFFF);
        String modsLabel = this.viewMode == ViewMode.MODS && this.selectedNamespace != null ? "< Mods" : "Mods";
        drawCenteredString(poseStack, this.font, new TextComponent(modsLabel),
                secondLeft + tabWidth / 2, TABS_TOP + 6, 0xFFFFFF);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int left = (this.width - PANEL_WIDTH) / 2;
            int innerWidth = PANEL_WIDTH - 24;
            int tabWidth = innerWidth / 2;
            int firstLeft = left + 12;
            int secondLeft = firstLeft + tabWidth;
            if (mouseY >= TABS_TOP && mouseY < TABS_TOP + TAB_HEIGHT) {
                if (mouseX >= firstLeft && mouseX < secondLeft) {
                    selectView(ViewMode.BIOMES);
                    return true;
                }
                if (mouseX >= secondLeft && mouseX < firstLeft + innerWidth) {
                    selectView(ViewMode.MODS);
                    return true;
                }
            }
        }

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
        if (showingBiomeRows()) {
            if (index >= 0 && index < this.filteredBiomes.size()) {
                NetworkHandler.CHANNEL.sendToServer(new SelectBiomePacket(this.filteredBiomes.get(index)));
                this.onClose();
                return true;
            }
        } else if (index >= 0 && index < this.filteredNamespaces.size()) {
            this.selectedNamespace = this.filteredNamespaces.get(index);
            applyFilter(this.searchBox.getValue());
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int visibleRows = Math.max(1, (this.height - LIST_BOTTOM_MARGIN - LIST_TOP) / ROW_HEIGHT);
        int rowCount = showingBiomeRows() ? this.filteredBiomes.size() : this.filteredNamespaces.size();
        int maxOffset = Math.max(0, rowCount - visibleRows);
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
        return friendlyWords(id.getPath()) + " [" + id.getNamespace() + "]";
    }

    private static String friendlyNamespace(String namespace) {
        return friendlyWords(namespace.replace('.', '_'));
    }

    private static String friendlyWords(String value) {
        String[] words = value.replace('-', '_').split("_");
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
        return result.toString();
    }

    private enum ViewMode {
        BIOMES,
        MODS
    }
}
