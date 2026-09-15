package com.akitaattribute.biomepockets.client;

import com.akitaattribute.biomepockets.network.NetworkHandler;
import com.akitaattribute.biomepockets.network.OpenPocketManagerPacket;
import com.akitaattribute.biomepockets.world.PocketClaimManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraft.resources.ResourceLocation;

public final class PocketBiomeManagerScreen extends Screen {
    private static final int PANEL_WIDTH = 340;
    private static final int PANEL_HEIGHT = 246;
    private static final int BUTTON_WIDTH = 270;
    private static final int BUTTON_HEIGHT = 22;
    private static final int BUTTON_GAP = 7;
    private static final int HEADER_HEIGHT = 70;
    private static final int PROGRESS_WIDTH = 270;
    private static final int PROGRESS_HEIGHT = 9;

    private final OpenPocketManagerPacket state;

    private Button claimButton;
    private Button unclaimButton;
    private ExpandButton expandButton;
    private Button visitButton;
    private Button exitButton;

    private boolean expansionProgressActive;
    private int expansionProgressCompleted;
    private int expansionProgressTotal;

    public PocketBiomeManagerScreen(OpenPocketManagerPacket state) {
        super(new TranslatableComponent("screen.biomepockets.pocket_manager"));
        this.state = state;
    }

    private int panelTop() {
        return Math.max(20, height / 2 - PANEL_HEIGHT / 2);
    }

    private int buttonTop() {
        return panelTop() + HEADER_HEIGHT;
    }

    @Override
    protected void init() {
        int left = (width - BUTTON_WIDTH) / 2;
        int top = buttonTop();

        claimButton = new Button(
                left,
                top,
                BUTTON_WIDTH,
                BUTTON_HEIGHT,
                new TextComponent("Claim"),
                button -> submit(PocketClaimManager.Action.CLAIM));
        addRenderableWidget(claimButton);

        unclaimButton = new Button(
                left,
                top + (BUTTON_HEIGHT + BUTTON_GAP),
                BUTTON_WIDTH,
                BUTTON_HEIGHT,
                new TextComponent("Unclaim"),
                button -> submit(PocketClaimManager.Action.UNCLAIM));
        addRenderableWidget(unclaimButton);

        expandButton = new ExpandButton(
                left,
                top + 2 * (BUTTON_HEIGHT + BUTTON_GAP),
                BUTTON_WIDTH,
                BUTTON_HEIGHT,
                expansionLabel(),
                expansionLevelEquivalent(),
                state.expansionCost(),
                state.creative() || state.availableXp() >= state.expansionCost(),
                button -> submit(PocketClaimManager.Action.EXPAND));
        addRenderableWidget(expandButton);

        visitButton = new Button(
                left,
                top + 3 * (BUTTON_HEIGHT + BUTTON_GAP),
                BUTTON_WIDTH,
                BUTTON_HEIGHT,
                new TextComponent("Visit"),
                button -> submit(PocketClaimManager.Action.VISIT));
        addRenderableWidget(visitButton);

        exitButton = new Button(
                left,
                top + 4 * (BUTTON_HEIGHT + BUTTON_GAP),
                BUTTON_WIDTH,
                BUTTON_HEIGHT,
                new TextComponent("Exit"),
                button -> submit(PocketClaimManager.Action.EXIT));
        addRenderableWidget(exitButton);

        applyButtonStates();
    }

    public void updateExpansionProgress(int completed, int total, boolean active) {
        expansionProgressActive = active;
        expansionProgressCompleted = Math.max(0, completed);
        expansionProgressTotal = Math.max(0, total);
        applyButtonStates();
        if (expandButton != null) {
            expandButton.setMessage(expansionLabel());
        }
    }

    private boolean expansionBusy() {
        return state.expanding() || expansionProgressActive;
    }

    private void applyButtonStates() {
        if (claimButton == null) {
            return;
        }
        boolean busy = expansionBusy();
        claimButton.active = state.canClaim() && !busy;
        unclaimButton.active = state.canUnclaim() && !busy;
        expandButton.active = state.canExpand() && !busy;
        visitButton.active = state.canVisit() && !busy;
        exitButton.active = state.canExit() && !busy;
    }

    private int expansionLevelEquivalent() {
        if (!state.hasClaim() || state.expansionCost() <= 0) {
            return 0;
        }

        int rawXp = state.expansionCost();
        int level = 0;
        while (totalXpForLevel(level + 1) <= rawXp && level < 21863) {
            level++;
        }
        return level;
    }

    private static long totalXpForLevel(int level) {
        if (level <= 16) {
            return (long) level * level + 6L * level;
        }
        if (level <= 31) {
            return (5L * level * level - 81L * level + 720L) / 2L;
        }
        return (9L * level * level - 325L * level + 4440L) / 2L;
    }

    private Component expansionLabel() {
        if (expansionBusy()) {
            return new TextComponent("Expand - currently generating");
        }
        if (!state.hasClaim()) {
            return new TextComponent("Expand");
        }
        return new TextComponent("Expand " + state.currentSize() + "x" + state.currentSize()
                + " -> " + state.nextSize() + "x" + state.nextSize());
    }

    private void submit(PocketClaimManager.Action action) {
        if (action == PocketClaimManager.Action.EXPAND) {
            // Expansion keeps this screen open. The server immediately replaces this
            // provisional zero-progress state with its actual completed/total work.
            expansionProgressActive = true;
            expansionProgressCompleted = 0;
            expansionProgressTotal = 1;
            applyButtonStates();
            if (expandButton != null) {
                expandButton.setMessage(expansionLabel());
            }
            NetworkHandler.sendPocketManagerAction(action);
            return;
        }

        NetworkHandler.sendPocketManagerAction(action);
        onClose();
    }

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        renderBackground(poseStack);

        int panelLeft = (width - PANEL_WIDTH) / 2;
        int panelTop = panelTop();
        int panelBottom = Math.min(height - 12, panelTop + PANEL_HEIGHT);
        fill(poseStack, panelLeft, panelTop, panelLeft + PANEL_WIDTH, panelBottom, 0xD8202020);
        fill(poseStack, panelLeft, panelTop, panelLeft + PANEL_WIDTH, panelTop + 1, 0xFFAAAAAA);

        drawCenteredString(
                poseStack,
                font,
                title,
                width / 2,
                panelTop + 10,
                0xFFFFFF);

        String status;
        int statusColor;
        if (expansionBusy()) {
            status = "Generating expanded pocket...";
            statusColor = 0xFFFFAA00;
        } else if (state.hasClaim()) {
            status = "Claimed: " + state.claimedDimension();
            statusColor = 0xFFAAAAAA;
        } else {
            status = "No permanent pocket claimed";
            statusColor = 0xFFAAAAAA;
        }
        drawCenteredString(poseStack, font, status, width / 2, panelTop + 29, statusColor);

        if (expansionBusy()) {
            renderExpansionProgress(poseStack, panelTop);
        }

        super.render(poseStack, mouseX, mouseY, partialTick);
    }

    private void renderExpansionProgress(PoseStack poseStack, int panelTop) {
        int left = (width - PROGRESS_WIDTH) / 2;
        int top = panelTop + 44;
        int right = left + PROGRESS_WIDTH;
        int bottom = top + PROGRESS_HEIGHT;

        fill(poseStack, left - 1, top - 1, right + 1, bottom + 1, 0xFFAAAAAA);
        fill(poseStack, left, top, right, bottom, 0xFF303030);

        int total = Math.max(1, expansionProgressTotal);
        int completed = Math.min(Math.max(0, expansionProgressCompleted), total);
        int fillWidth = (int) Math.round((double) PROGRESS_WIDTH * completed / total);
        if (fillWidth > 0) {
            fill(poseStack, left, top, left + fillWidth, bottom, 0xFF55AA55);
        }

        String progressText;
        if (expansionProgressTotal > 0) {
            int percent = (int) Math.floor(100.0D * completed / total);
            progressText = percent + "%";
        } else {
            progressText = "Starting...";
        }
        drawCenteredString(poseStack, font, progressText, width / 2, top + 11, 0xFFCCCCCC);
    }

    private static final class ExpandButton extends Button {
        private static final ResourceLocation ENCHANTMENT_TABLE_GUI =
                new ResourceLocation("textures/gui/container/enchanting_table.png");
        private static final int XP_ORB_CROP_WIDTH = 11;

        private final int levelEquivalentCost;
        private final int rawXpCost;
        private final boolean canAfford;

        private ExpandButton(
                int x,
                int y,
                int width,
                int height,
                Component message,
                int levelEquivalentCost,
                int rawXpCost,
                boolean canAfford,
                OnPress onPress) {
            super(x, y, width, height, message, onPress);
            this.levelEquivalentCost = levelEquivalentCost;
            this.rawXpCost = rawXpCost;
            this.canAfford = canAfford;
        }

        @Override
        public void renderButton(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
            super.renderButton(poseStack, mouseX, mouseY, partialTick);
            if (rawXpCost <= 0 || levelEquivalentCost <= 0) {
                return;
            }

            RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
            RenderSystem.setShaderTexture(0, ENCHANTMENT_TABLE_GUI);

            Minecraft minecraft = Minecraft.getInstance();
            String levelCost = Integer.toString(levelEquivalentCost);
            String rawCost = "(" + rawXpCost + " XP)";
            int levelWidth = minecraft.font.width(levelCost);
            int rawWidth = minecraft.font.width(rawCost);
            int groupWidth = XP_ORB_CROP_WIDTH + 2 + levelWidth + 4 + rawWidth;
            int iconX = x + width - groupWidth - 8;
            int iconY = y + 3;

            blit(
                    poseStack,
                    iconX,
                    iconY,
                    0,
                    223 + (!canAfford ? 16 : 0),
                    XP_ORB_CROP_WIDTH,
                    16);

            int levelColor = canAfford ? 0xC8FF8F : 0xFF7777;
            int textY = y + 7;
            int levelX = iconX + XP_ORB_CROP_WIDTH + 2;
            minecraft.font.draw(poseStack, levelCost, levelX, textY, levelColor);
            minecraft.font.draw(
                    poseStack,
                    rawCost,
                    levelX + levelWidth + 4,
                    textY,
                    canAfford ? 0xAAAAAA : 0xCC7777);

            if (isHoveredOrFocused() && mouseX >= iconX - 2) {
                String tooltip = levelEquivalentCost + "-level equivalent = " + rawXpCost + " XP points";
                tooltip = (canAfford ? ChatFormatting.GREEN : ChatFormatting.RED) + tooltip;
                Minecraft.getInstance().screen.renderTooltip(
                        poseStack,
                        new TextComponent(tooltip),
                        mouseX,
                        mouseY);
            }
        }
    }
}
