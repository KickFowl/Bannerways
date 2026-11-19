package com.simibubi.create_bannerways.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create_bannerways.PhysicsFlagController;
import com.simibubi.create_bannerways.entity.FlagEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;

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

    @Override
    protected int getBlockLightLevel(FlagEntity entity, BlockPos pos) {
        BlockPos samplePos = clothCenterBlockPos(entity, pos);
        return Math.max(super.getBlockLightLevel(entity, samplePos),
                entity.level().getBrightness(LightLayer.BLOCK, samplePos));
    }

    @Override
    protected int getSkyLightLevel(FlagEntity entity, BlockPos pos) {
        BlockPos samplePos = clothCenterBlockPos(entity, pos);
        return Math.max(super.getSkyLightLevel(entity, samplePos),
                entity.level().getBrightness(LightLayer.SKY, samplePos));
    }

    private static BlockPos clothCenterBlockPos(FlagEntity entity, BlockPos fallback) {
        PhysicsFlagController flag = entity.physicsController;
        if (flag == null || flag.particles.length == 0) return fallback.below();
        PhysicsFlagController.Particle mid = flag.particles[(flag.gridH / 2) * flag.gridW + (flag.gridW / 2)];
        return BlockPos.containing(mid.x, mid.y, mid.z);
    }

    @Override
    public boolean shouldRender(FlagEntity entity, Frustum frustum, double camX, double camY, double camZ) {
        // No frustum test (the cloth extends well beyond the entity's box), but respect the render distance
        return entity.shouldRender(camX, camY, camZ);
    }

    @Override
    public void render(FlagEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight) {
        PhysicsFlagController flag = entity.physicsController;
        if (flag == null) return;

        int gridW = flag.gridW;
        int gridH = flag.gridH;
        PhysicsFlagController.Particle[] ps = flag.particles;
        Level level = entity.level();

        double ex = Mth.lerp(partialTick, entity.xo, entity.getX());
        double ey = Mth.lerp(partialTick, entity.yo, entity.getY());
        double ez = Mth.lerp(partialTick, entity.zo, entity.getZ());

        double[] rx = new double[ps.length];
        double[] ry = new double[ps.length];
        double[] rz = new double[ps.length];
        int[] vBlock = new int[ps.length];
        int[] vSky = new int[ps.length];

        // Sample per-vertex light independently (stepping 0.45 blocks out only if inside an occluding block)
        for (int i = 0; i < ps.length; i++) {
            PhysicsFlagController.Particle p = ps[i];
            double wx = Mth.lerp(partialTick, p.px, p.x);
            double wy = Mth.lerp(partialTick, p.py, p.y);
            double wz = Mth.lerp(partialTick, p.pz, p.z);
            if (p.pinned && i < gridW) {
                Vec3 pinned = flag.renderPinnedPos(i, partialTick);
                if (pinned != null) { wx = pinned.x; wy = pinned.y; wz = pinned.z; }
            }
            rx[i] = wx - ex;
            ry[i] = wy - ey;
            rz[i] = wz - ez;

            BlockPos bp = BlockPos.containing(wx, wy, wz);
            int l = LevelRenderer.getLightColor(level, bp);
            if (level.getBlockState(bp).isSolidRender(level, bp)) {
                int lBelow = LevelRenderer.getLightColor(level, BlockPos.containing(wx, wy - 0.6, wz));
                int lSide1 = LevelRenderer.getLightColor(level, BlockPos.containing(wx + 0.6, wy, wz));
                int lSide2 = LevelRenderer.getLightColor(level, BlockPos.containing(wx, wy, wz + 0.6));
                l = maxLight(l, maxLight(lBelow, maxLight(lSide1, lSide2)));
            }
            vBlock[i] = LightTexture.block(l);
            vSky[i] = LightTexture.sky(l);
        }

        // Smooth light values locally across neighbors so block boundaries don't show on cloth cells
        int[] smoothLight = new int[ps.length];
        for (int y = 0; y < gridH; y++) {
            int ym = Math.max(y - 1, 0), yp = Math.min(y + 1, gridH - 1);
            for (int x = 0; x < gridW; x++) {
                int xm = Math.max(x - 1, 0), xp = Math.min(x + 1, gridW - 1);
                int i = x + y * gridW;
                int bSum = vBlock[i] * 2 + vBlock[xm + y * gridW] + vBlock[xp + y * gridW] + vBlock[x + ym * gridW] + vBlock[x + yp * gridW];
                int sSum = vSky[i] * 2 + vSky[xm + y * gridW] + vSky[xp + y * gridW] + vSky[x + ym * gridW] + vSky[x + yp * gridW];
                int bAvg = Math.min(15, (bSum + 3) / 6);
                int sAvg = Math.min(15, (sSum + 3) / 6);
                smoothLight[i] = LightTexture.pack(bAvg, sAvg);
            }
        }

        // Compute smooth per-vertex normals via central differences
        double[] vnx = new double[ps.length];
        double[] vny = new double[ps.length];
        double[] vnz = new double[ps.length];
        for (int y = 0; y < gridH; y++) {
            int ym = Math.max(y - 1, 0);
            int yp = Math.min(y + 1, gridH - 1);
            for (int x = 0; x < gridW; x++) {
                int xm = Math.max(x - 1, 0);
                int xp = Math.min(x + 1, gridW - 1);
                int i = x + y * gridW;

                int il = xm + y * gridW;
                int ir = xp + y * gridW;
                int iu = x + ym * gridW;
                int id = x + yp * gridW;

                double ax = rx[ir] - rx[il], ay = ry[ir] - ry[il], az = rz[ir] - rz[il];
                double bx = rx[id] - rx[iu], by = ry[id] - ry[iu], bz = rz[id] - rz[iu];
                double nx = ay * bz - az * by;
                double ny = az * bx - ax * bz;
                double nz = ax * by - ay * bx;
                double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (len < 1.0E-9) {
                    vnx[i] = 0; vny[i] = 1; vnz[i] = 0;
                } else {
                    vnx[i] = nx / len; vny[i] = ny / len; vnz[i] = nz / len;
                }
            }
        }

        // 2 passes of Laplacian normal smoothing to hide cloth grid cells,
        // then blend skyward (+Y) so both sides are bright and evenly lit under Iris
        double[] tmpX = new double[ps.length];
        double[] tmpY = new double[ps.length];
        double[] tmpZ = new double[ps.length];
        for (int pass = 0; pass < 2; pass++) {
            for (int y = 0; y < gridH; y++) {
                int ym = Math.max(y - 1, 0), yp = Math.min(y + 1, gridH - 1);
                for (int x = 0; x < gridW; x++) {
                    int xm = Math.max(x - 1, 0), xp = Math.min(x + 1, gridW - 1);
                    int i = x + y * gridW;
                    int il = xm + y * gridW, ir = xp + y * gridW;
                    int iu = x + ym * gridW, id = x + yp * gridW;
                    double sx = vnx[i] * 2 + vnx[il] + vnx[ir] + vnx[iu] + vnx[id];
                    double sy = vny[i] * 2 + vny[il] + vny[ir] + vny[iu] + vny[id];
                    double sz = vnz[i] * 2 + vnz[il] + vnz[ir] + vnz[iu] + vnz[id];
                    double len = Math.sqrt(sx * sx + sy * sy + sz * sz);
                    if (len > 1.0E-9) {
                        tmpX[i] = sx / len; tmpY[i] = sy / len; tmpZ[i] = sz / len;
                    } else {
                        tmpX[i] = 0; tmpY[i] = 1; tmpZ[i] = 0;
                    }
                }
            }
            System.arraycopy(tmpX, 0, vnx, 0, ps.length);
            System.arraycopy(tmpY, 0, vny, 0, ps.length);
            System.arraycopy(tmpZ, 0, vnz, 0, ps.length);
        }

        for (int i = 0; i < ps.length; i++) {
            double sx = vnx[i] * 0.25;
            double sy = vny[i] * 0.25 + 0.75;
            double sz = vnz[i] * 0.25;
            double len = Math.sqrt(sx * sx + sy * sy + sz * sz);
            vnx[i] = sx / len;
            vny[i] = sy / len;
            vnz[i] = sz / len;
        }

        if (PhysicsFlagController.DEBUG && !loggedFirstRender) {
            loggedFirstRender = true;
            LOG.info("[create_bannerways] Renderer running: {} particles", ps.length);
        }

        poseStack.pushPose();
        PoseStack.Pose pose = poseStack.last();
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

                vertex(vc, pose, rx[i0], ry[i0], rz[i0], u0, v0, vnx[i0], vny[i0], vnz[i0], smoothLight[i0]);
                vertex(vc, pose, rx[i1], ry[i1], rz[i1], u1, v0, vnx[i1], vny[i1], vnz[i1], smoothLight[i1]);
                vertex(vc, pose, rx[i2], ry[i2], rz[i2], u1, v1, vnx[i2], vny[i2], vnz[i2], smoothLight[i2]);
                vertex(vc, pose, rx[i3], ry[i3], rz[i3], u0, v1, vnx[i3], vny[i3], vnz[i3], smoothLight[i3]);
            }
        }

        poseStack.popPose();
    }

    private static int maxLight(int a, int b) {
        int block = Math.max(LightTexture.block(a), LightTexture.block(b));
        int sky = Math.max(LightTexture.sky(a), LightTexture.sky(b));
        return LightTexture.pack(block, sky);
    }

    private static void vertex(VertexConsumer vc, PoseStack.Pose pose, double x, double y, double z, float u, float v,
                               double nx, double ny, double nz, int light) {
        vc.addVertex(pose, (float) x, (float) y, (float) z)
          .setColor(255, 255, 255, 255)
          .setUv(u, v)
          .setOverlay(OverlayTexture.NO_OVERLAY)
          .setLight(light)
          .setNormal(pose, (float) nx, (float) ny, (float) nz);
    }

    @Override
    public ResourceLocation getTextureLocation(FlagEntity entity) {
        String rawHash = entity.getImageHash();
        String cacheKey = (rawHash != null && !rawHash.isEmpty())
                ? rawHash
                : "default_" + entity.getFlagWidth() + "x" + entity.getFlagHeight();

        ResourceLocation cached = TEXTURE_CACHE.get(cacheKey);
        if (cached != null) return cached;

        byte[] rawPixels = entity.getPixels();
        int w = Math.max(entity.getFlagWidth() * 16, 16);
        int h = Math.max(entity.getFlagHeight() * 16, 16);

        NativeImage image = new NativeImage(NativeImage.Format.RGBA, w, h, true);
        if (rawPixels != null && rawPixels.length > 0) {
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
        TEXTURE_CACHE.put(cacheKey, resLoc);
        return resLoc;
    }
}
