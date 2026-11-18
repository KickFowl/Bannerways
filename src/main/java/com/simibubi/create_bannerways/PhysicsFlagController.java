package com.simibubi.create_bannerways;

import com.mojang.logging.LogUtils;
import com.simibubi.create_bannerways.ModRegistration.FlagData;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.slf4j.Logger;

/**
 * Verlet cloth simulation, run entirely in WORLD space.
 *
 * - Gravity is a constant world -Y, so it points down regardless of how a ship is tilted.
 * - The pinned top row follows the connectors: on a Sable sub-level the connector's plot-space position
 *   is projected to world space with the sub-level's pose every tick.
 * - The cloth's horizontal rest length is the ACTUAL distance between the two connectors (not the nominal
 *   flag width), so a flag hung between two close connectors stretches its texture instead of crumpling
 *   into a thin ribbon.
 * - Block collision: terrain is checked at the particle's world position; ship blocks are checked by
 *   taking the particle back into the sub-level's plot space, pushing it out there, and projecting back.
 * - Wind comes from FlagWind and acts relative to each particle's velocity.
 */
public class PhysicsFlagController {
    private static final Logger LOG = LogUtils.getLogger();
    /** Prints a status line every 5 seconds per flag. Set to false once things work. */
    public static final boolean DEBUG = true;

    private static final int SUBDIV = 4;
    private static final int MIN_ITERATIONS = 12;
    private static final int MAX_ITERATIONS = 40;
    private static final double GRAVITY = 0.012;
    private static final double DAMPING = 0.97;
    private static final double THICKNESS = 0.03;
    private static final double AERO_NORMAL = 0.05;
    private static final double AERO_TANGENT = 0.004;
    private static final double MAX_AERO = 0.15;
    private static final double MAX_SPEED = 0.5;
    private static final double SLACK = 1.02;        // horizontal rest length = connector distance * SLACK

    // constraint kinds
    private static final byte H1 = 0, V1 = 1, H2 = 2, V2 = 3;

    public final BlockPos startConnector;
    public final BlockPos endConnector;
    public final int width;
    public final int height;
    public final byte[] pixelData;

    public final int gridW;
    public final int gridH;
    public final Particle[] particles;

    private final int[] ca, cb;
    private final byte[] kind;
    private final double[] rest, stiff;
    private final int iterations;

    private boolean initialized = false;
    private int ticks = 0;

    public PhysicsFlagController(BlockPos start, BlockPos end, FlagData data) {
        this.startConnector = start;
        this.endConnector = end;
        this.width = data.width();
        this.height = data.height();
        this.pixelData = data.customPixels().orElse(new byte[0]);

        this.gridW = width * SUBDIV;
        this.gridH = height * SUBDIV;
        this.particles = new Particle[gridW * gridH];
        for (int i = 0; i < particles.length; i++) particles[i] = new Particle();
        this.iterations = Math.min(MAX_ITERATIONS, MIN_ITERATIONS + gridH / 2);

        double dy = (double) height / (gridH - 1);

        int count = 0;
        int[] a = new int[particles.length * 4];
        int[] b = new int[particles.length * 4];
        byte[] k = new byte[particles.length * 4];
        double[] r = new double[particles.length * 4];
        double[] s = new double[particles.length * 4];
        for (int y = 0; y < gridH; y++) {
            for (int x = 0; x < gridW; x++) {
                int i = x + y * gridW;
                if (x < gridW - 1) { a[count] = i; b[count] = i + 1;         k[count] = H1; r[count] = 0;      s[count++] = 1.0; }
                if (y < gridH - 1) { a[count] = i; b[count] = i + gridW;     k[count] = V1; r[count] = dy;     s[count++] = 1.0; }
                if (x < gridW - 2) { a[count] = i; b[count] = i + 2;         k[count] = H2; r[count] = 0;      s[count++] = 0.5; }
                if (y < gridH - 2) { a[count] = i; b[count] = i + gridW * 2; k[count] = V2; r[count] = 2 * dy; s[count++] = 0.5; }
            }
        }
        this.ca = java.util.Arrays.copyOf(a, count);
        this.cb = java.util.Arrays.copyOf(b, count);
        this.kind = java.util.Arrays.copyOf(k, count);
        this.rest = java.util.Arrays.copyOf(r, count);
        this.stiff = java.util.Arrays.copyOf(s, count);
    }

    private void init(Vec3 w1, Vec3 w2) {
        double span = Math.max(w1.distanceTo(w2), 0.5);
        double dx = span * SLACK / (gridW - 1);
        for (int k = 0; k < rest.length; k++) {
            if (kind[k] == H1) rest[k] = dx;
            else if (kind[k] == H2) rest[k] = 2 * dx;
        }

        for (int y = 0; y < gridH; y++) {
            double py = (double) y / (gridH - 1);
            for (int x = 0; x < gridW; x++) {
                double px = (double) x / (gridW - 1);
                Particle p = particles[x + y * gridW];
                p.set(w1.x + (w2.x - w1.x) * px,
                      w1.y + (w2.y - w1.y) * px - py * height,
                      w1.z + (w2.z - w1.z) * px);
                p.pinned = (y == 0);
            }
        }
        initialized = true;
        if (DEBUG) {
            LOG.info("[create_bannerways] Flag initialised: {} x {} blocks, connector span {} blocks, {} particles, {} constraints, {} iterations",
                    width, height, String.format("%.2f", span), particles.length, ca.length, iterations);
        }
    }

    public void tick(Level level) {
        Vec3 c1 = Vec3.atCenterOf(startConnector);
        Vec3 c2 = Vec3.atCenterOf(endConnector);

        SubLevelAccess sub = SableCompanion.INSTANCE.getContaining(level, startConnector);
        Pose3dc pose = sub != null ? sub.logicalPose() : null;
        Vec3 w1 = pose != null ? pose.transformPosition(c1) : c1;
        Vec3 w2 = pose != null ? pose.transformPosition(c2) : c2;

        if (!initialized) {
            init(w1, w2);
            return;
        }

        for (int x = 0; x < gridW; x++) {
            double t = (double) x / (gridW - 1);
            Particle p = particles[x];
            p.px = p.x; p.py = p.y; p.pz = p.z;
            p.x = w1.x + (w2.x - w1.x) * t;
            p.y = w1.y + (w2.y - w1.y) * t;
            p.z = w1.z + (w2.z - w1.z) * t;
        }

        Vec3 centre = new Vec3((w1.x + w2.x) * 0.5, (w1.y + w2.y) * 0.5 - height * 0.5, (w1.z + w2.z) * 0.5);
        Vec3 wind = FlagWind.sample(level, centre);
        double time = level.getGameTime() * 0.08;

        for (int y = 0; y < gridH; y++) {
            for (int x = 0; x < gridW; x++) {
                Particle p = particles[x + y * gridW];
                if (p.pinned) continue;

                double vx = p.x - p.px, vy = p.y - p.py, vz = p.z - p.pz;

                Particle l = particles[Math.max(x - 1, 0) + y * gridW];
                Particle r = particles[Math.min(x + 1, gridW - 1) + y * gridW];
                Particle u = particles[x + Math.max(y - 1, 0) * gridW];
                Particle d = particles[x + Math.min(y + 1, gridH - 1) * gridW];
                double ax = r.x - l.x, ay = r.y - l.y, az = r.z - l.z;
                double bx = d.x - u.x, by = d.y - u.y, bz = d.z - u.z;
                double nx = ay * bz - az * by;
                double ny = az * bx - ax * bz;
                double nz = ax * by - ay * bx;
                double nl = Math.sqrt(nx * nx + ny * ny + nz * nz);

                double accX = 0, accY = -GRAVITY, accZ = 0;
                if (nl > 1.0E-9) {
                    nx /= nl; ny /= nl; nz /= nl;
                    double gust = 1.0 + 0.35 * Math.sin(time * 1.7 + x * 0.35 + y * 0.2);
                    double rx = wind.x * gust - vx, ry = wind.y * gust - vy, rz = wind.z * gust - vz;
                    double nd = rx * nx + ry * ny + rz * nz;
                    double fx = AERO_NORMAL * nd * nx + AERO_TANGENT * (rx - nd * nx);
                    double fy = AERO_NORMAL * nd * ny + AERO_TANGENT * (ry - nd * ny);
                    double fz = AERO_NORMAL * nd * nz + AERO_TANGENT * (rz - nd * nz);
                    double fl = Math.sqrt(fx * fx + fy * fy + fz * fz);
                    if (fl > MAX_AERO) { double kk = MAX_AERO / fl; fx *= kk; fy *= kk; fz *= kk; }
                    accX += fx; accY += fy; accZ += fz;
                }

                double nvx = vx * DAMPING + accX;
                double nvy = vy * DAMPING + accY;
                double nvz = vz * DAMPING + accZ;
                double sp = Math.sqrt(nvx * nvx + nvy * nvy + nvz * nvz);
                if (sp > MAX_SPEED) { double kk = MAX_SPEED / sp; nvx *= kk; nvy *= kk; nvz *= kk; }

                p.px = p.x; p.py = p.y; p.pz = p.z;
                p.x += nvx;
                p.y += nvy;
                p.z += nvz;
            }
        }

        for (int it = 0; it < iterations; it++) {
            for (int k = 0; k < ca.length; k++) solve(k);
            if (it == iterations / 2) collideAll(level, pose);
        }
        collideAll(level, pose);

        if (DEBUG && (++ticks % 100) == 0) {
            Particle mid = particles[gridW / 2 + (gridH - 1) * gridW];
            Particle top = particles[gridW / 2];
            int bad = 0;
            for (Particle p : particles) if (!Double.isFinite(p.x + p.y + p.z)) bad++;
            LOG.info("[create_bannerways] flag@{} onShip={} top=({},{},{}) bottom=({},{},{}) wind=({},{},{}) nonFinite={}",
                    startConnector.toShortString(), sub != null,
                    f(top.x), f(top.y), f(top.z), f(mid.x), f(mid.y), f(mid.z),
                    f(wind.x), f(wind.y), f(wind.z), bad);
        }
    }

    private static String f(double v) { return String.format("%.2f", v); }

    private void solve(int k) {
        Particle a = particles[ca[k]];
        Particle b = particles[cb[k]];
        double dx = b.x - a.x, dy = b.y - a.y, dz = b.z - a.z;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist < 1.0E-9) return;

        double wa = a.pinned ? 0 : 1;
        double wb = b.pinned ? 0 : 1;
        double total = wa + wb;
        if (total == 0) return;

        double f = (rest[k] - dist) / dist * stiff[k];
        double fa = f * wa / total;
        double fb = f * wb / total;
        a.x -= dx * fa; a.y -= dy * fa; a.z -= dz * fa;
        b.x += dx * fb; b.y += dy * fb; b.z += dz * fb;
    }

    private void collideAll(Level level, Pose3dc pose) {
        for (Particle p : particles) {
            if (p.pinned) continue;

            double[] out = pushOut(level, p.x, p.y, p.z);
            if (out != null) {
                p.x = out[0]; p.y = out[1]; p.z = out[2];
            }

            if (pose != null) {
                Vec3 local = pose.transformPositionInverse(new Vec3(p.x, p.y, p.z));
                double[] pushed = pushOut(level, local.x, local.y, local.z);
                if (pushed != null) {
                    Vec3 world = pose.transformPosition(new Vec3(pushed[0], pushed[1], pushed[2]));
                    p.x = world.x; p.y = world.y; p.z = world.z;
                    p.px = p.x; p.py = p.y; p.pz = p.z;
                }
            }
        }
    }

    private double[] pushOut(Level level, double x, double y, double z) {
        BlockPos bp = BlockPos.containing(x, y, z);
        if (bp.equals(startConnector) || bp.equals(endConnector)) return null;

        BlockState state = level.getBlockState(bp);
        if (state.isAir()) return null;
        VoxelShape shape = state.getCollisionShape(level, bp);
        if (shape.isEmpty()) return null;

        double lx = x - bp.getX(), ly = y - bp.getY(), lz = z - bp.getZ();
        for (AABB box : shape.toAabbs()) {
            if (lx <= box.minX || lx >= box.maxX || ly <= box.minY || ly >= box.maxY || lz <= box.minZ || lz >= box.maxZ) {
                continue;
            }
            double[] pen = {
                    lx - box.minX, box.maxX - lx,
                    ly - box.minY, box.maxY - ly,
                    lz - box.minZ, box.maxZ - lz
            };
            int best = 0;
            for (int i = 1; i < 6; i++) if (pen[i] < pen[best]) best = i;

            double[] res = {x, y, z};
            switch (best) {
                case 0 -> res[0] = bp.getX() + box.minX - THICKNESS;
                case 1 -> res[0] = bp.getX() + box.maxX + THICKNESS;
                case 2 -> res[1] = bp.getY() + box.minY - THICKNESS;
                case 3 -> res[1] = bp.getY() + box.maxY + THICKNESS;
                case 4 -> res[2] = bp.getZ() + box.minZ - THICKNESS;
                default -> res[2] = bp.getZ() + box.maxZ + THICKNESS;
            }
            return res;
        }
        return null;
    }

    public static class Particle {
        public double x, y, z;
        public double px, py, pz;
        public boolean pinned = false;

        void set(double x, double y, double z) {
            this.x = this.px = x;
            this.y = this.py = y;
            this.z = this.pz = z;
        }
    }
}