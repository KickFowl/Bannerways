package com.simibubi.create_bannerways.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create_bannerways.PhysicsFlagController;
import com.simibubi.create_bannerways.entity.FlagEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class FlagEntityRenderer extends EntityRenderer<FlagEntity> {

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();
    private static boolean loggedFirstRender = false;
    private static final Map<String, ResourceLocation> TEXTURE_CACHE = new HashMap<>();

    private static final int[] PALETTE = {
            0xFFFFFFFF, 0xFFD87F33, 0xFFB24CD8, 0xFF6699D8,
            0xFFE5E533, 0xFF7FCC19, 0xFFF27FA5, 0xFF4C4C4C,
            0xFF999999, 0xFF4C7F99, 0xFF7F3FB2, 0xFF334CB2,
            0xFF664C33, 0xFF667F33, 0xFF993333, 0xFF191919
    };

    public FlagEntityRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    /** The flag's real extent is the cloth, not the entity's tiny hitbox. Never frustum-cull it. */
    @Override
    public boolean shouldRender(FlagEntity entity, Frustum frustum, double camX, double camY, double camZ) {
        return true;
    }

    @Override
    public void render(FlagEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight) {
        PhysicsFlagController flag = entity.physicsController;
        if (flag == null) return;

        int gridW = flag.gridW;
        int gridH = flag.gridH;
        PhysicsFlagController.Particle[] ps = flag.particles;

        // Particles are simulated in WORLD space (the controller projects ship connectors out of plot
        // space), so no Sable transform is needed here. We ignore the pose stack's translation (the
        // dispatcher built it from the entity's plot position) and emit camera-relative vertices.
        Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();

        double[] wx = new double[ps.length];
        double[] wy = new double[ps.length];
        double[] wz = new double[ps.length];
        for (int i = 0; i < ps.length; i++) {
            PhysicsFlagController.Particle p = ps[i];
            Vec3 local = new Vec3(
                    p.px + (p.x - p.px) * partialTick,
                    p.py + (p.y - p.py) * partialTick,
                    p.pz + (p.z - p.pz) * partialTick);
            wx[i] = local.x - cam.x;
            wy[i] = local.y - cam.y;
            wz[i] = local.z - cam.z;
        }

        if (PhysicsFlagController.DEBUG && !loggedFirstRender) {
            loggedFirstRender = true;
            LOG.info("[create_bannerways] Renderer running: {} particles, first particle camera-relative=({}, {}, {}), cam=({}, {}, {})",
                    ps.length, wx[0], wy[0], wz[0], cam.x, cam.y, cam.z);
        }

        VertexConsumer vc = buffer.getBuffer(RenderType.entityCutoutNoCull(getTextureLocation(entity)));

        for (int y = 0; y < gridH - 1; y++) {
            for (int x = 0; x < gridW - 1; x++) {
                int i0 = x + y * gridW;
                int i1 = i0 + 1;
                int i3 = i0 + gridW;
                int i2 = i3 + 1;

                float u0 = (float) x / (gridW - 1);
                float v0 = (float) y / (gridH - 1);
                float u1 = (float) (x + 1) / (gridW - 1);
                float v1 = (float) (y + 1) / (gridH - 1);

                double ax = wx[i1] - wx[i0], ay = wy[i1] - wy[i0], az = wz[i1] - wz[i0];
                double bx = wx[i3] - wx[i0], by = wy[i3] - wy[i0], bz = wz[i3] - wz[i0];
                double nx = ay * bz - az * by;
                double ny = az * bx - ax * bz;
                double nz = ax * by - ay * bx;
                double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (len < 1.0E-9) { nx = 0; ny = 0; nz = 1; } else { nx /= len; ny /= len; nz /= len; }

                vertex(vc, wx[i0], wy[i0], wz[i0], u0, v0, nx, ny, nz, packedLight);
                vertex(vc, wx[i1], wy[i1], wz[i1], u1, v0, nx, ny, nz, packedLight);
                vertex(vc, wx[i2], wy[i2], wz[i2], u1, v1, nx, ny, nz, packedLight);
                vertex(vc, wx[i3], wy[i3], wz[i3], u0, v1, nx, ny, nz, packedLight);
            }
        }
    }

    private static void vertex(VertexConsumer vc, double x, double y, double z, float u, float v,
                               double nx, double ny, double nz, int light) {
        vc.addVertex((float) x, (float) y, (float) z)
          .setColor(255, 255, 255, 255)
          .setUv(u, v)
          .setOverlay(OverlayTexture.NO_OVERLAY)
          .setLight(light)
          .setNormal((float) nx, (float) ny, (float) nz);
    }

    @Override
    public ResourceLocation getTextureLocation(FlagEntity entity) {
        String hash = entity.getImageHash();
        ResourceLocation cached = TEXTURE_CACHE.get(hash);
        if (cached != null) return cached;

        byte[] rawPixels = entity.getPixels();
        int w = Math.max(entity.getFlagWidth() * 16, 16);
        int h = Math.max(entity.getFlagHeight() * 16, 16);

        NativeImage image = new NativeImage(NativeImage.Format.RGBA, w, h, true);
        if (rawPixels.length > 0) {
            for (int i = 0; i < rawPixels.length && i < w * h; i++) {
                int palIdx = rawPixels[i] & 0xFF;
                if (palIdx >= PALETTE.length) palIdx = 0;
                int color = PALETTE[palIdx];
                int abgr = (color & 0xFF000000) | ((color & 0xFF) << 16) | (color & 0xFF00) | ((color >> 16) & 0xFF);
                image.setPixelRGBA(i % w, i / w, abgr);
            }
        } else {
            image.fillRect(0, 0, w, h, 0xFFFFFFFF);
        }

        DynamicTexture dynTex = new DynamicTexture(image);
        ResourceLocation resLoc = Minecraft.getInstance().getTextureManager()
                .register("flag_entity_" + UUID.randomUUID().toString().substring(0, 6), dynTex);
        TEXTURE_CACHE.put(hash, resLoc);
        return resLoc;
    }
}