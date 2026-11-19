package com.simibubi.create_bannerways.gui;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.simibubi.create_bannerways.ModRegistration;
import com.simibubi.create_bannerways.network.NetworkPackets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class FlagStationScreen extends AbstractContainerScreen<FlagStationMenu> {
    private static final ResourceLocation BG = ResourceLocation.fromNamespaceAndPath(ModRegistration.MODID, "textures/gui/flag_station.png");

    private int flagWidth = 8;
    private int flagHeight = 5;

    // Resolution: 16x16 per block
    private byte[] pixelGrid;

    // Standard Minecraft Dyes Mapping (White to Black)
    private final int[] PALETTE = {
            0xFFFFFFFF, 0xFFD87F33, 0xFFB24CD8, 0xFF6699D8,
            0xFFE5E533, 0xFF7FCC19, 0xFFF27FA5, 0xFF4C4C4C,
            0xFF999999, 0xFF4C7F99, 0xFF7F3FB2, 0xFF334CB2,
            0xFF664C33, 0xFF667F33, 0xFF993333, 0xFF191919
    };
    private int selectedColorIdx = 15; // Start Black

    // Native GPU Texture for Zoom/Pan Performance
    private NativeImage canvasImage;
    private DynamicTexture canvasTexture;
    private ResourceLocation canvasRes;
    private boolean needsTextureUpload = false;

    // Viewport Controls
    private float zoom = 1.0f;
    private float panX = 0f;
    private float panY = 0f;

    // Exact UI Layout Constants matched to flag_station.png (256x188)
    private static final int CANVAS_X = 61, CANVAS_Y = 41, CANVAS_W = 93, CANVAS_H = 102;
    private static final int PALETTE_X = 163, PALETTE_Y = 44, COLOR_SIZE = 5, COLOR_STEP = 6;

    private Button btnIncW, btnDecW, btnIncH, btnDecH, btnFabricate, btnImportPng;
    private EditBox pathInputBox;

    public FlagStationScreen(FlagStationMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = 256;
        this.imageHeight = 188;
        initPixelGrid();
    }

    private void initPixelGrid() {
        int w = flagWidth * 16;
        int h = flagHeight * 16;
        this.pixelGrid = new byte[w * h];
        Arrays.fill(pixelGrid, (byte) 0); // Fill White

        if (canvasTexture != null) canvasTexture.close();

        this.canvasImage = new NativeImage(NativeImage.Format.RGBA, w, h, true);
        this.canvasTexture = new DynamicTexture(canvasImage);
        this.canvasRes = Minecraft.getInstance().getTextureManager().register("flag_canvas_" + UUID.randomUUID().toString().substring(0, 5), canvasTexture);

        rebuildTexture();
        zoom = 1.0f; panX = 0f; panY = 0f;
    }

    private void rebuildTexture() {
        int w = flagWidth * 16;
        for (int i = 0; i < pixelGrid.length; i++) {
            int color = PALETTE[pixelGrid[i] & 0xFF];
            int abgr = (color & 0xFF000000) | ((color & 0xFF) << 16) | (color & 0xFF00) | ((color >> 16) & 0xFF);
            canvasImage.setPixelRGBA(i % w, i / w, abgr);
        }
        needsTextureUpload = true;
    }

    @Override
    protected void init() {
        super.init();

        // Width controls (left side, matching reference)
        btnDecW = Button.builder(Component.literal("-"), b -> { if (this.flagWidth > 2) { this.flagWidth--; initPixelGrid(); } })
                .bounds(leftPos + 20, topPos + 58, 12, 12).build();
        btnIncW = Button.builder(Component.literal("+"), b -> { if (this.flagWidth < 10 && (this.flagWidth + 1) * this.flagHeight <= NetworkPackets.MAX_FLAG_AREA) { this.flagWidth++; initPixelGrid(); } })
                .bounds(leftPos + 54, topPos + 58, 12, 12).build();

        // Height controls
        btnDecH = Button.builder(Component.literal("-"), b -> { if (this.flagHeight > 1) { this.flagHeight--; initPixelGrid(); } })
                .bounds(leftPos + 20, topPos + 92, 12, 12).build();
        btnIncH = Button.builder(Component.literal("+"), b -> { if (this.flagHeight < 20 && this.flagWidth * (this.flagHeight + 1) <= NetworkPackets.MAX_FLAG_AREA) { this.flagHeight++; initPixelGrid(); } })
                .bounds(leftPos + 54, topPos + 92, 12, 12).build();

        // Local PNG Import inside right brown panel (x: 176..244, y: 41..106)
        pathInputBox = new EditBox(font, leftPos + 180, topPos + 56, 60, 11, Component.literal("PNG Path"));
        pathInputBox.setMaxLength(512);
        pathInputBox.setBordered(false);
        this.addWidget(pathInputBox);

        btnImportPng = Button.builder(Component.literal("Import PNG"), b -> importLocalPng(pathInputBox.getValue()))
                .bounds(leftPos + 180, topPos + 70, 60, 12).build();

        // Direct Fabricate inside gear-framed button (x: 188..238, y: 126..138)
        btnFabricate = Button.builder(Component.literal("FABRICATE!"), b -> sendUpdateToServer())
                .bounds(leftPos + 188, topPos + 126, 50, 12).build();

        addRenderableWidget(btnDecW); addRenderableWidget(btnIncW);
        addRenderableWidget(btnDecH); addRenderableWidget(btnIncH);
        addRenderableWidget(pathInputBox); addRenderableWidget(btnImportPng);
        addRenderableWidget(btnFabricate);
    }

    private void importLocalPng(String rawPath) {
        CompletableFuture.runAsync(() -> {
            try {
                File file = null;
                String trimmed = rawPath != null ? rawPath.trim().replace("\"", "") : "";
                if (!trimmed.isEmpty()) {
                    File candidate = new File(trimmed);
                    if (candidate.isFile()) {
                        file = candidate;
                    }
                }

                if (file == null) {
                    try (MemoryStack stack = MemoryStack.stackPush()) {
                        PointerBuffer filters = stack.mallocPointer(1);
                        filters.put(stack.UTF8("*.png"));
                        filters.flip();
                        String chosen = TinyFileDialogs.tinyfd_openFileDialog(
                                "Select Flag PNG",
                                System.getProperty("user.home") + File.separator,
                                filters,
                                "PNG Images (*.png)",
                                false
                        );
                        if (chosen == null || chosen.isBlank()) return;
                        file = new File(chosen);
                    }
                }

                if (!file.isFile()) return;
                BufferedImage sourceImage = ImageIO.read(file);
                if (sourceImage == null) return;

                int finalResW = this.flagWidth * 16;
                int finalResH = this.flagHeight * 16;
                BufferedImage rescaled = new BufferedImage(finalResW, finalResH, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = rescaled.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(sourceImage, 0, 0, finalResW, finalResH, null);
                g.dispose();

                for (int y = 0; y < finalResH; y++) {
                    for (int x = 0; x < finalResW; x++) {
                        this.pixelGrid[x + y * finalResW] = mapColorToPalette(rescaled.getRGB(x, y));
                    }
                }

                String selectedPath = file.getAbsolutePath();
                Minecraft.getInstance().execute(() -> {
                    pathInputBox.setValue(selectedPath);
                    rebuildTexture();
                });
            } catch (Exception ignored) {}
        });
    }

    private byte mapColorToPalette(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        byte bestMatch = 0;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < PALETTE.length; i++) {
            double dist = Math.pow(r - ((PALETTE[i] >> 16) & 0xFF), 2) + Math.pow(g - ((PALETTE[i] >> 8) & 0xFF), 2) + Math.pow(b - (PALETTE[i] & 0xFF), 2);
            if (dist < bestDist) { bestDist = dist; bestMatch = (byte) i; }
        }
        return bestMatch;
    }

    // --- INTERACTION: Zoom, Pan, Paint ---
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (isHoveringCanvas(mouseX, mouseY)) {
            float oldZoom = zoom;
            zoom = Math.clamp(zoom + (float) scrollY * 0.25f, 0.5f, 10.0f);

            double relX = mouseX - leftPos - CANVAS_X - panX;
            double relY = mouseY - topPos - CANVAS_Y - panY;
            panX -= (float) (relX * (zoom / oldZoom - 1.0f));
            panY -= (float) (relY * (zoom / oldZoom - 1.0f));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (isHoveringCanvas(mouseX, mouseY)) {
            if (button == 0) { // Left Click = Pan
                panX += (float) dragX;
                panY += (float) dragY;
                return true;
            } else if (button == 1) { // Right Click = Paint
                paintPixel(mouseX, mouseY);
                return true;
            }
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 1 && isHoveringCanvas(mouseX, mouseY)) {
            paintPixel(mouseX, mouseY);
            return true;
        }
        for (int i = 0; i < 16; i++) {
            int px = leftPos + PALETTE_X;
            int py = topPos + PALETTE_Y + (i * COLOR_STEP);
            if (mouseX >= px && mouseX < px + COLOR_SIZE && mouseY >= py && mouseY < py + COLOR_SIZE) {
                this.selectedColorIdx = i;
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean isHoveringCanvas(double mx, double my) {
        return mx >= leftPos + CANVAS_X && mx <= leftPos + CANVAS_X + CANVAS_W &&
               my >= topPos + CANVAS_Y && my <= topPos + CANVAS_Y + CANVAS_H;
    }

    private void paintPixel(double mouseX, double mouseY) {
        float baseScale = Math.min((float) CANVAS_W / (flagWidth * 16), (float) CANVAS_H / (flagHeight * 16));
        double gridX = (mouseX - leftPos - CANVAS_X - panX) / (baseScale * zoom);
        double gridY = (mouseY - topPos - CANVAS_Y - panY) / (baseScale * zoom);

        int px = (int) gridX;
        int py = (int) gridY;

        if (px >= 0 && px < flagWidth * 16 && py >= 0 && py < flagHeight * 16) {
            int idx = px + py * (flagWidth * 16);
            if (pixelGrid[idx] != (byte) selectedColorIdx) {
                pixelGrid[idx] = (byte) selectedColorIdx;
                int color = PALETTE[selectedColorIdx];
                int abgr = (color & 0xFF000000) | ((color & 0xFF) << 16) | (color & 0xFF00) | ((color >> 16) & 0xFF);
                canvasImage.setPixelRGBA(px, py, abgr);
                needsTextureUpload = true;
            }
        }
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        // Suppress default inventory/title labels so we render exact reference labels
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        RenderSystem.setShaderTexture(0, BG);
        guiGraphics.blit(BG, leftPos, topPos, 0, 0, imageWidth, imageHeight);

        // Title & section headers matching reference image
        guiGraphics.drawCenteredString(font, "FLAG STATION DESIGNER", leftPos + 128, topPos + 14, 0xFFFFFFFF);
        guiGraphics.drawCenteredString(font, "Drawing Canvas", leftPos + 107, topPos + 31, 0xFFFFFFFF);
        guiGraphics.drawCenteredString(font, "Palette", leftPos + 166, topPos + 31, 0xFFFFFFFF);
        guiGraphics.drawCenteredString(font, "Import Local PNG", leftPos + 210, topPos + 45, 0xFFFFFFFF);
        guiGraphics.drawCenteredString(font, "Resource Cost", leftPos + 210, topPos + 85, 0xFFFFFFFF);

        // Width & Height value boxes
        guiGraphics.drawCenteredString(font, "Width", leftPos + 43, topPos + 48, 0xFFFFFFFF);
        guiGraphics.fill(leftPos + 34, topPos + 58, leftPos + 52, topPos + 70, 0xFF000000);
        guiGraphics.renderOutline(leftPos + 34, topPos + 58, 18, 12, 0xFF555555);
        guiGraphics.drawCenteredString(font, String.valueOf(flagWidth), leftPos + 43, topPos + 60, 0xFFFFFFFF);

        guiGraphics.drawCenteredString(font, "Height", leftPos + 43, topPos + 82, 0xFFFFFFFF);
        guiGraphics.fill(leftPos + 34, topPos + 92, leftPos + 52, topPos + 104, 0xFF000000);
        guiGraphics.renderOutline(leftPos + 34, topPos + 92, 18, 12, 0xFF555555);
        guiGraphics.drawCenteredString(font, String.valueOf(flagHeight), leftPos + 43, topPos + 94, 0xFFFFFFFF);

        // Dark input box background for PNG path
        guiGraphics.fill(leftPos + 179, topPos + 55, leftPos + 241, topPos + 67, 0xFF000000);
        guiGraphics.renderOutline(leftPos + 179, topPos + 55, 62, 12, 0xFF555555);

        if (needsTextureUpload) {
            canvasTexture.upload();
            needsTextureUpload = false;
        }

        // Render hardware accelerated canvas using Scissor bounds
        guiGraphics.enableScissor(leftPos + CANVAS_X, topPos + CANVAS_Y, leftPos + CANVAS_X + CANVAS_W, topPos + CANVAS_Y + CANVAS_H);
        float baseScale = Math.min((float) CANVAS_W / (flagWidth * 16), (float) CANVAS_H / (flagHeight * 16));
        int drawW = (int) (flagWidth * 16 * baseScale * zoom);
        int drawH = (int) (flagHeight * 16 * baseScale * zoom);

        guiGraphics.blit(canvasRes, leftPos + CANVAS_X + (int) panX, topPos + CANVAS_Y + (int) panY, 0, 0, drawW, drawH, drawW, drawH);
        guiGraphics.disableScissor();

        // Render Palette
        for (int i = 0; i < 16; i++) {
            int py = topPos + PALETTE_Y + (i * COLOR_STEP);
            guiGraphics.fill(leftPos + PALETTE_X, py, leftPos + PALETTE_X + COLOR_SIZE, py + COLOR_SIZE, PALETTE[i]);
            if (i == selectedColorIdx) {
                guiGraphics.renderOutline(leftPos + PALETTE_X - 1, py - 1, COLOR_SIZE + 2, COLOR_SIZE + 2, 0xFFFFD700);
            }
        }

        renderCostOverlay(guiGraphics);
    }

    private void renderCostOverlay(GuiGraphics guiGraphics) {
        int[] dyeCounts = new int[16];
        for (byte b : pixelGrid) {
            int idx = b & 0xFF;
            if (idx < 16) dyeCounts[idx]++;
        }

        int woolCost = flagWidth * flagHeight; // 1 Wool per block
        guiGraphics.renderItem(new ItemStack(Items.WHITE_WOOL), leftPos + 178, topPos + 93);
        guiGraphics.drawString(font, "x" + woolCost, leftPos + 194, topPos + 98, 0xFFFFFF, true);

        // Render ALL required colored dyes (up to 3 inline, or "+N" indicator if more)
        int renderedDyes = 0;
        int extraDyes = 0;
        for (int i = 1; i < 16; i++) {
            int cost = (int) Math.ceil(dyeCounts[i] / 256.0);
            if (cost > 0) {
                if (renderedDyes < 2) {
                    ItemStack repDye = new ItemStack(NetworkPackets.DYE_ITEMS[i]);
                    int dx = leftPos + 208 + (renderedDyes * 18);
                    guiGraphics.renderItem(repDye, dx, topPos + 93);
                    guiGraphics.drawString(font, String.valueOf(cost), dx + 11, topPos + 100, 0xFFFFFF, true);
                    renderedDyes++;
                } else {
                    extraDyes++;
                }
            }
        }
        if (extraDyes > 0) {
            guiGraphics.drawString(font, "+" + extraDyes, leftPos + 244, topPos + 98, 0xFFD700, true);
        }
    }

    private void sendUpdateToServer() {
        PacketDistributor.sendToServer(new NetworkPackets.FabricateFlagPacket(
                this.menu.getBlockPos(), this.flagWidth, this.flagHeight, UUID.randomUUID().toString(), this.pixelGrid
        ));
    }

    @Override
    public void removed() {
        super.removed();
        if (canvasTexture != null) canvasTexture.close();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        this.renderTooltip(guiGraphics, mouseX, mouseY);
    }
}
