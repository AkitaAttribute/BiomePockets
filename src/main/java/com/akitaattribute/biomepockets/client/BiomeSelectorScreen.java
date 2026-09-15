package com.akitaattribute.biomepockets.client;

import com.akitaattribute.biomepockets.network.NetworkHandler;
import com.akitaattribute.biomepockets.network.SelectBiomePacket;
import com.akitaattribute.biomepockets.network.UpdateDebugSettingsPacket;
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
    private static final int[] PROBE_AXES = { 0, 1, 3, 5, 7, 9 };
    private static final int MAX_GENERATION_OPS = 16;

    private final List<ResourceLocation> allBiomes;
    private final List<String> allNamespaces;
    private final boolean debugEditable;
    private List<ResourceLocation> filteredBiomes;
    private List<String> filteredNamespaces;
    private EditBox searchBox;
    private ViewMode viewMode = ViewMode.BIOMES;
    private String selectedNamespace;
    private int scrollOffset;
    private int heightProbeAxis;
    private int generationOpsPerTick;

    public BiomeSelectorScreen(
            List<ResourceLocation> biomes,
            int heightProbeAxis,
            int generationOpsPerTick,
            boolean debugEditable) {
        super(new TextComponent("Biome Transporter Selector"));
        this.allBiomes = new ArrayList<>(biomes);
        this.filteredBiomes = new ArrayList<>(biomes);
        this.allNamespaces = biomes.stream()
                .map(ResourceLocation::getNamespace)
                .distinct()
                .sorted()
                .toList();
        this.filteredNamespaces = new ArrayList<>(this.allNamespaces);
        this.heightProbeAxis = normalizeProbeAxis(heightProbeAxis);
        this.generationOpsPerTick = Mth.clamp(generationOpsPerTick, 1, MAX_GENERATION_OPS);
        this.debugEditable = debugEditable;
    }

    @Override
    protected void init() {
        int left = (this.width - PANEL_WIDTH) / 2;
        this.searchBox = new EditBox(this.font, left + 12, 42, PANEL_WIDTH - 24, 20, new TextComponent("Search biomes"));
        this.searchBox.setMaxLength(100);
        this.searchBox.setResponder(this::applyFilter);
        this.addRenderableWidget(this.searchBox);
        applyFilter(this.searchBox.getValue());
        updateSearchVisibility();
    }

    private void applyFilter(String query) {
        if (this.viewMode == ViewMode.DEBUG) {
            this.filteredBiomes = List.of();
            this.filteredNamespaces = List.of();
            this.scrollOffset = 0;
            return;
        }

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
        return this.viewMode != ViewMode.DEBUG
                && (query.startsWith("#") || this.viewMode == ViewMode.BIOMES || this.selectedNamespace != null);
    }

    private void selectView(ViewMode mode) {
        if (mode == ViewMode.MODS && this.viewMode == ViewMode.MODS && this.selectedNamespace != null) {
            this.selectedNamespace = null;
        } else {
            this.viewMode = mode;
            this.selectedNamespace = null;
        }
        updateSearchVisibility();
        applyFilter(this.searchBox == null ? "" : this.searchBox.getValue());
    }

    private void updateSearchVisibility() {
        if (this.searchBox != null) {
            this.searchBox.visible = this.viewMode != ViewMode.DEBUG;
        }
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

        if (this.viewMode == ViewMode.DEBUG) {
            renderDebug(poseStack, left, right);
        } else {
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
        }

        super.render(poseStack, mouseX, mouseY, partialTick);
    }

    private void renderTabs(PoseStack poseStack, int left) {
        int innerWidth = PANEL_WIDTH - 24;
        int tabWidth = innerWidth / 3;
        int firstLeft = left + 12;
        int secondLeft = firstLeft + tabWidth;
        int thirdLeft = secondLeft + tabWidth;
        int tabsRight = firstLeft + innerWidth;

        fill(poseStack, firstLeft, TABS_TOP, secondLeft - 1, TABS_TOP + TAB_HEIGHT,
                this.viewMode == ViewMode.BIOMES ? 0xFF4B4B4B : 0xFF292929);
        fill(poseStack, secondLeft + 1, TABS_TOP, thirdLeft - 1, TABS_TOP + TAB_HEIGHT,
                this.viewMode == ViewMode.MODS ? 0xFF4B4B4B : 0xFF292929);
        fill(poseStack, thirdLeft + 1, TABS_TOP, tabsRight, TABS_TOP + TAB_HEIGHT,
                this.viewMode == ViewMode.DEBUG ? 0xFF4B4B4B : 0xFF292929);

        drawCenteredString(poseStack, this.font, new TextComponent("Biomes"),
                firstLeft + tabWidth / 2, TABS_TOP + 6, 0xFFFFFF);
        String modsLabel = this.viewMode == ViewMode.MODS && this.selectedNamespace != null ? "< Mods" : "Mods";
        drawCenteredString(poseStack, this.font, new TextComponent(modsLabel),
                secondLeft + tabWidth / 2, TABS_TOP + 6, 0xFFFFFF);
        drawCenteredString(poseStack, this.font, new TextComponent("Debug"),
                thirdLeft + tabWidth / 2, TABS_TOP + 6, 0xFFFFFF);
    }

    private void renderDebug(PoseStack poseStack, int left, int right) {
        int x = left + 18;
        int rowRight = right - 18;
        int firstY = LIST_TOP + 12;
        int secondY = firstY + 66;

        this.font.draw(poseStack, "Runtime generation tuning", x, firstY, 0xFFFFFFFF);
        this.font.draw(poseStack,
                this.debugEditable ? "Changes apply server-wide until restart." : "Operator permission is required on multiplayer.",
                x, firstY + 12, 0xFFAAAAAA);

        renderDebugControl(poseStack, x, rowRight, firstY + 30,
                "Height probes", heightProbeLabel());
        this.font.draw(poseStack,
                "Candidate terrain probes per seed. More = stricter/slower.",
                x, firstY + 53, 0xFF888888);

        renderDebugControl(poseStack, x, rowRight, secondY + 18,
                "Generation ops / tick", Integer.toString(this.generationOpsPerTick));
        this.font.draw(poseStack,
                "Max chunk-status requests submitted/in flight. 1 = safe default.",
                x, secondY + 41, 0xFF888888);
    }

    private void renderDebugControl(PoseStack poseStack, int left, int right, int y, String label, String value) {
        fill(poseStack, left, y, right, y + 20, 0xAA292929);
        this.font.draw(poseStack, label, left + 6, y + 6, 0xFFFFFFFF);
        int minusLeft = right - 112;
        int plusLeft = right - 24;
        fill(poseStack, minusLeft, y + 1, minusLeft + 22, y + 19, this.debugEditable ? 0xFF4B4B4B : 0xFF333333);
        fill(poseStack, plusLeft, y + 1, plusLeft + 22, y + 19, this.debugEditable ? 0xFF4B4B4B : 0xFF333333);
        drawCenteredString(poseStack, this.font, new TextComponent("-"), minusLeft + 11, y + 6, 0xFFFFFFFF);
        drawCenteredString(poseStack, this.font, new TextComponent("+"), plusLeft + 11, y + 6, 0xFFFFFFFF);
        drawCenteredString(poseStack, this.font, new TextComponent(value), right - 57, y + 6, 0xFFFFFFFF);
    }

    private String heightProbeLabel() {
        if (this.heightProbeAxis == 0) {
            return "Auto (9/25)";
        }
        return Integer.toString(this.heightProbeAxis * this.heightProbeAxis)
                + " (" + this.heightProbeAxis + "x" + this.heightProbeAxis + ")";
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int left = (this.width - PANEL_WIDTH) / 2;
            int innerWidth = PANEL_WIDTH - 24;
            int tabWidth = innerWidth / 3;
            int firstLeft = left + 12;
            int secondLeft = firstLeft + tabWidth;
            int thirdLeft = secondLeft + tabWidth;
            if (mouseY >= TABS_TOP && mouseY < TABS_TOP + TAB_HEIGHT) {
                if (mouseX >= firstLeft && mouseX < secondLeft) {
                    selectView(ViewMode.BIOMES);
                    return true;
                }
                if (mouseX >= secondLeft && mouseX < thirdLeft) {
                    selectView(ViewMode.MODS);
                    return true;
                }
                if (mouseX >= thirdLeft && mouseX < firstLeft + innerWidth) {
                    selectView(ViewMode.DEBUG);
                    return true;
                }
            }

            if (this.viewMode == ViewMode.DEBUG && handleDebugClick(mouseX, mouseY, left)) {
                return true;
            }
        }

        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button != 0 || this.viewMode == ViewMode.DEBUG) {
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

    private boolean handleDebugClick(double mouseX, double mouseY, int panelLeft) {
        if (!this.debugEditable) {
            return false;
        }

        int left = panelLeft + 18;
        int right = panelLeft + PANEL_WIDTH - 18;
        int firstY = LIST_TOP + 42;
        int secondY = LIST_TOP + 96;
        int minusLeft = right - 112;
        int plusLeft = right - 24;

        boolean changed = false;
        if (mouseY >= firstY && mouseY < firstY + 20) {
            if (mouseX >= minusLeft && mouseX < minusLeft + 22) {
                this.heightProbeAxis = cycleProbeAxis(this.heightProbeAxis, -1);
                changed = true;
            } else if (mouseX >= plusLeft && mouseX < plusLeft + 22) {
                this.heightProbeAxis = cycleProbeAxis(this.heightProbeAxis, 1);
                changed = true;
            }
        } else if (mouseY >= secondY && mouseY < secondY + 20) {
            if (mouseX >= minusLeft && mouseX < minusLeft + 22) {
                this.generationOpsPerTick = Math.max(1, this.generationOpsPerTick - 1);
                changed = true;
            } else if (mouseX >= plusLeft && mouseX < plusLeft + 22) {
                this.generationOpsPerTick = Math.min(MAX_GENERATION_OPS, this.generationOpsPerTick + 1);
                changed = true;
            }
        }

        if (changed) {
            NetworkHandler.CHANNEL.sendToServer(new UpdateDebugSettingsPacket(
                    this.heightProbeAxis,
                    this.generationOpsPerTick));
        }
        return changed;
    }

    private static int cycleProbeAxis(int current, int direction) {
        int index = 0;
        for (int i = 0; i < PROBE_AXES.length; i++) {
            if (PROBE_AXES[i] == current) {
                index = i;
                break;
            }
        }
        index = Mth.clamp(index + direction, 0, PROBE_AXES.length - 1);
        return PROBE_AXES[index];
    }

    private static int normalizeProbeAxis(int value) {
        for (int allowed : PROBE_AXES) {
            if (value == allowed) {
                return value;
            }
        }
        return 0;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (this.viewMode == ViewMode.DEBUG) {
            return false;
        }
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
        MODS,
        DEBUG
    }
}
