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
    private static final int PANEL_HEIGHT = 226;
    private static final int BUTTON_WIDTH = 270;
    private static final int BUTTON_HEIGHT = 22;
    private static final int BUTTON_GAP = 7;
    private static final int HEADER_HEIGHT = 50;

    private final OpenPocketManagerPacket state;

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

        Button claim = new Button(
                left,
                top,
                BUTTON_WIDTH,
                BUTTON_HEIGHT,
                new TextComponent("Claim"),
                button -> submit(PocketClaimManager.Action.CLAIM));
        claim.active = state.canClaim();
        addRenderableWidget(claim);

        Button unclaim = new Button(
                left,
                top + (BUTTON_HEIGHT + BUTTON_GAP),
                BUTTON_WIDTH,
                BUTTON_HEIGHT,
                new TextComponent("Unclaim"),
                button -> submit(PocketClaimManager.Action.UNCLAIM));
        unclaim.active = state.canUnclaim();
        addRenderableWidget(unclaim);

        ExpandButton expand = new ExpandButton(
                left,
                top + 2 * (BUTTON_HEIGHT + BUTTON_GAP),
                BUTTON_WIDTH,
                BUTTON_HEIGHT,
                expansionLabel(),
                expansionLevelEquivalent(),
                state.expansionCost(),
                state.creative() || state.availableXp() >= state.expansionCost(),
                button -> submit(PocketClaimManager.Action.EXPAND));
        expand.active = state.canExpand();
        addRenderableWidget(expand);

        Button visit = new Button(
                left,
                top + 3 * (BUTTON_HEIGHT + BUTTON_GAP),
                BUTTON_WIDTH,
                BUTTON_HEIGHT,
                new TextComponent("Visit"),
                button -> submit(PocketClaimManager.Action.VISIT));
        visit.active = state.canVisit();
        addRenderableWidget(visit);

        Button exit = new Button(
                left,
                top + 4 * (BUTTON_HEIGHT + BUTTON_GAP),
                BUTTON_WIDTH,
                BUTTON_HEIGHT,
                new TextComponent("Exit"),
                button -> submit(PocketClaimManager.Action.EXIT));
        exit.active = state.canExit();
        addRenderableWidget(exit);
    }

    private int expansionLevelEquivalent() {
        if (!state.hasClaim() || state.expansionCost() <= 0) {
            return 0;
        }

        // The server charges raw XP points. Display the highest vanilla experience
        // level whose cumulative XP requirement is <= that raw cost. For example:
        // 1395 XP = level 30 exactly, while 2790 XP = level 39 plus partial progress
        // toward level 40, so the displayed equivalent is 39 rather than 60.
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
        if (state.expanding()) {
            return new TextComponent("Expand - currently generating");
        }
        if (!state.hasClaim()) {
            return new TextComponent("Expand");
        }
        return new TextComponent("Expand " + state.currentSize() + "x" + state.currentSize()
                + " -> " + state.nextSize() + "x" + state.nextSize());
    }

    private void submit(PocketClaimManager.Action action) {
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
        if (state.expanding()) {
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

        super.render(poseStack, mouseX, mouseY, partialTick);
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

            // Waystones uses one of the vanilla enchanting-table 1/2/3 requirement
            // sprites. Those sprites contain their numeral, so draw only the orb part
            // of the first sprite and render our calculated level equivalent ourselves.
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
