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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
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
    
    // UI Layout Constants (Based on provided asset)
    private final int CANVAS_X = 63, CANVAS_Y = 49, CANVAS_W = 88, CANVAS_H = 88;
    private final int PALETTE_X = 159, PALETTE_Y = 50, COLOR_SIZE = 6;
    
    private Button btnIncW, btnDecW, btnIncH, btnDecH, btnFabricate, btnLoadUrl;
    private EditBox urlInputBox;

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
        zoom = 1.0f; panX = 0f; panY = 0f; // Reset viewport
    }

    private void rebuildTexture() {
        int w = flagWidth * 16;
        for (int i = 0; i < pixelGrid.length; i++) {
            int color = PALETTE[pixelGrid[i]];
            // Convert standard hex (AARRGGBB) to NativeImage expected (AABBGGRR)
            int abgr = (color & 0xFF000000) | ((color & 0xFF) << 16) | (color & 0xFF00) | ((color >> 16) & 0xFF);
            canvasImage.setPixelRGBA(i % w, i / w, abgr);
        }
        needsTextureUpload = true;
    }

    @Override
    protected void init() {
        super.init();

        // Dimensions
        btnDecW = Button.builder(Component.literal("-"), b -> { if (this.flagWidth > 2) { this.flagWidth--; initPixelGrid(); } })
                .bounds(leftPos + 22, topPos + 51, 10, 10).build();
        btnIncW = Button.builder(Component.literal("+"), b -> { if (this.flagWidth < 10) { this.flagWidth++; initPixelGrid(); } })
                .bounds(leftPos + 38, topPos + 51, 10, 10).build();
        btnDecH = Button.builder(Component.literal("-"), b -> { if (this.flagHeight > 1) { this.flagHeight--; initPixelGrid(); } })
                .bounds(leftPos + 22, topPos + 80, 10, 10).build();
        btnIncH = Button.builder(Component.literal("+"), b -> { if (this.flagHeight < 20) { this.flagHeight++; initPixelGrid(); } })
                .bounds(leftPos + 38, topPos + 80, 10, 10).build();

        // Downloads
        urlInputBox = new EditBox(font, leftPos + 175, topPos + 57, 68, 10, Component.literal("URL"));
        urlInputBox.setMaxLength(256);
        urlInputBox.setBordered(false);
        this.addWidget(urlInputBox);

        btnLoadUrl = Button.builder(Component.literal("Import & Downscale"), b -> fetchAndConvertUrl(urlInputBox.getValue()))
                .bounds(leftPos + 172, topPos + 71, 75, 12).build();

        // Direct Fabricate
        btnFabricate = Button.builder(Component.literal("FABRICATE!"), b -> sendUpdateToServer())
                .bounds(leftPos + 180, topPos + 118, 56, 18).build();

        addRenderableWidget(btnDecW); addRenderableWidget(btnIncW); addRenderableWidget(btnDecH); addRenderableWidget(btnIncH);
        addRenderableWidget(urlInputBox); addRenderableWidget(btnLoadUrl); addRenderableWidget(btnFabricate);
    }

    private void fetchAndConvertUrl(String link) {
        if (link == null || link.trim().isEmpty()) return;
        CompletableFuture.runAsync(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URI(link).toURL().openConnection();
                conn.setRequestProperty("User-Agent", "Minecraft");
                try (InputStream stream = conn.getInputStream()) {
                    BufferedImage sourceImage = ImageIO.read(stream);
                    if (sourceImage != null) {
                        int finalResW = this.flagWidth * 16;
                        int finalResH = this.flagHeight * 16;
                        BufferedImage rescaled = new BufferedImage(finalResW, finalResH, BufferedImage.TYPE_INT_RGB);
                        rescaled.createGraphics().drawImage(sourceImage, 0, 0, finalResW, finalResH, null);

                        for (int y = 0; y < finalResH; y++) {
                            for (int x = 0; x < finalResW; x++) {
                                this.pixelGrid[x + y * finalResW] = mapColorToPalette(rescaled.getRGB(x, y));
                            }
                        }
                        Minecraft.getInstance().execute(this::rebuildTexture);
                    }
                }
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
            zoom = Math.clamp(zoom + (float)scrollY * 0.25f, 0.5f, 10.0f);
            
            // Zoom towards mouse coordinate
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
        // Palette Check
        for (int i = 0; i < 16; i++) {
            int px = leftPos + PALETTE_X;
            int py = topPos + PALETTE_Y + (i * (COLOR_SIZE + 1)); // Tightly packed UI boxes
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
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        RenderSystem.setShaderTexture(0, BG);
        guiGraphics.blit(BG, leftPos, topPos, 0, 0, imageWidth, imageHeight);

        guiGraphics.drawString(font, flagWidth + " x " + flagHeight, leftPos + 22, topPos + 38, 0xFFFFFFFF, false);

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
            int py = topPos + PALETTE_Y + (i * (COLOR_SIZE + 1));
            guiGraphics.fill(leftPos + PALETTE_X, py, leftPos + PALETTE_X + COLOR_SIZE, py + COLOR_SIZE, PALETTE[i]);
            if (i == selectedColorIdx) {
                guiGraphics.renderOutline(leftPos + PALETTE_X - 1, py - 1, COLOR_SIZE + 2, COLOR_SIZE + 2, 0xFFFFD700);
            }
        }

        renderCostOverlay(guiGraphics);
    }

    private void renderCostOverlay(GuiGraphics guiGraphics) {
        int[] dyeCounts = new int[16];
        for (byte b : pixelGrid) dyeCounts[b]++;
        
        int woolCost = flagWidth * flagHeight; // 1 Wool per block
        guiGraphics.renderItem(new ItemStack(Items.WHITE_WOOL), leftPos + 175, topPos + 95);
        guiGraphics.drawString(font, "x" + woolCost, leftPos + 192, topPos + 99, 0xFFFFFF, true);

        // Dynamically shift dye icons to fit
        int renderedDyes = 0;
        for (int i = 0; i < 16; i++) {
            int cost = (int) Math.ceil(dyeCounts[i] / 8.0); // 1 dye per 8 pixels drawn
            if (cost > 0 && renderedDyes < 2) { // Fit max 2 most used dyes on UI preview for neatness
                ItemStack repDye = new ItemStack(NetworkPackets.DYE_ITEMS[i]); // Fetches mapping from Network class
                int dx = leftPos + 215 + (renderedDyes * 30);
                guiGraphics.renderItem(repDye, dx, topPos + 95);
                guiGraphics.drawString(font, "x" + cost, dx + 16, topPos + 99, 0xFFFFFF, true);
                renderedDyes++;
            }
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
        if (canvasTexture != null) canvasTexture.close(); // Prevent GPU memory leak
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        this.renderTooltip(guiGraphics, mouseX, mouseY);
    }
}