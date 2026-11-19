package com.simibubi.create_bannerways;

import com.mojang.logging.LogUtils;
import com.simibubi.create_bannerways.ModRegistration.FlagData;
import dev.ryanhcode.sable.companion.ClientSubLevelAccess;
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

import java.util.Arrays;

/**
 * Verlet cloth simulation in WORLD space with:
 * - Fixed cloth dimensions from the Flag Station table (droops when hung closer, taut at full width)
 * - Optional rope attachment: pins every 5th pivot point equally spaced along the rope path
 * - World & Sable ship block collision
 * - Spatial-hash self-collision so cloth never intersects itself
 */
public class PhysicsFlagController {
    private static final Logger LOG = LogUtils.getLogger();
    public static final boolean DEBUG = false;

    private static final int SUBDIV = 4;
    private static final int MIN_ITERATIONS = 16;
    private static final int MAX_ITERATIONS = 48;
    private static final double GRAVITY = 0.014;
    private static final double DAMPING = 0.96;
    private static final double THICKNESS = 0.04;
    private static final double AERO_NORMAL = 0.05;
    private static final double AERO_TANGENT = 0.004;
    private static final double MAX_AERO = 0.15;
    private static final double MAX_SPEED = 0.5;

    private static final byte H1 = 0, V1 = 1, H2 = 2, V2 = 3, SHEAR = 4;

    public BlockPos startConnector;
    public BlockPos endConnector;
    public boolean hasRope = false;

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

    private final double selfMinDist;
    private final double selfCellSize;
    private final int hashTableSize;
    private final int[] hashHead;
    private final int[] hashNext;

    private boolean initialized = false;
    private int ticks = 0;

    public PhysicsFlagController(BlockPos start, BlockPos end, FlagData data) {
        this.startConnector = start;
        this.endConnector = end;
        this.width = Math.max(2, data.width());
        this.height = Math.max(1, data.height());
        this.pixelData = data.customPixels().orElse(new byte[0]);

        this.gridW = width * SUBDIV;
        this.gridH = height * SUBDIV;
        this.particles = new Particle[gridW * gridH];
        for (int i = 0; i < particles.length; i++) particles[i] = new Particle();
        this.iterations = Math.min(MAX_ITERATIONS, MIN_ITERATIONS + gridH / 2);

        double dx = (double) width / (gridW - 1);
        double dy = (double) height / (gridH - 1);
        double diag = Math.hypot(dx, dy);

        this.selfMinDist = Math.clamp(Math.min(dx, dy) * 0.85, 0.12, 0.25);
        this.selfCellSize = this.selfMinDist * 1.15;
        this.hashTableSize = Math.max(1024, Integer.highestOneBit(particles.length * 4) << 1);
        this.hashHead = new int[hashTableSize];
        this.hashNext = new int[particles.length];

        int count = 0;
        int maxConstraints = particles.length * 6;
        int[] a = new int[maxConstraints];
        int[] b = new int[maxConstraints];
        byte[] k = new byte[maxConstraints];
        double[] r = new double[maxConstraints];
        double[] s = new double[maxConstraints];
        for (int y = 0; y < gridH; y++) {
            for (int x = 0; x < gridW; x++) {
                int i = x + y * gridW;
                if (x < gridW - 1) { a[count] = i; b[count] = i + 1;         k[count] = H1; r[count] = dx;     s[count++] = 1.0; }
                if (y < gridH - 1) { a[count] = i; b[count] = i + gridW;     k[count] = V1; r[count] = dy;     s[count++] = 1.0; }
                if (x < gridW - 2) { a[count] = i; b[count] = i + 2;         k[count] = H2; r[count] = 2 * dx; s[count++] = 0.5; }
                if (y < gridH - 2) { a[count] = i; b[count] = i + gridW * 2; k[count] = V2; r[count] = 2 * dy; s[count++] = 0.5; }
                if (x < gridW - 1 && y < gridH - 1) {
                    a[count] = i;     b[count] = i + gridW + 1; k[count] = SHEAR; r[count] = diag; s[count++] = 0.5;
                    a[count] = i + 1; b[count] = i + gridW;     k[count] = SHEAR; r[count] = diag; s[count++] = 0.5;
                }
            }
        }
        this.ca = Arrays.copyOf(a, count);
        this.cb = Arrays.copyOf(b, count);
        this.kind = Arrays.copyOf(k, count);
        this.rest = Arrays.copyOf(r, count);
        this.stiff = Arrays.copyOf(s, count);
    }

    public void setConnectors(BlockPos start, BlockPos end) {
        this.startConnector = start;
        this.endConnector = end;
    }

    public void setHasRope(boolean hasRope) {
        this.hasRope = hasRope;
        updateTopPins();
    }

    private void updateTopPins() {
        for (int x = 0; x < gridW; x++) {
            particles[x].pinned = isTopPivot(x);
        }
    }

    private boolean isTopPivot(int x) {
        if (x == 0 || x == gridW - 1) return true;
        return hasRope && (x % 5 == 0);
    }

    private void init(Vec3 w1, Vec3 w2) {
        double horizDist = Math.hypot(w2.x - w1.x, w2.z - w1.z);
        double vertDist = Math.abs(w2.y - w1.y);
        boolean verticalPole = horizDist < 0.25 && vertDist > 0.25;

        double span = w1.distanceTo(w2);
        double sag = Math.max(0.0, width - span) * 0.35;

        for (int y = 0; y < gridH; y++) {
            double py = (double) y / (gridH - 1);
            for (int x = 0; x < gridW; x++) {
                double px = (double) x / (gridW - 1);
                Particle p = particles[x + y * gridW];
                if (verticalPole) {
                    p.set(w1.x + (w2.x - w1.x) * px + py * height,
                          w1.y + (w2.y - w1.y) * px,
                          w1.z + (w2.z - w1.z) * px);
                } else {
                    double droop = Math.sin(px * Math.PI) * sag;
                    p.set(w1.x + (w2.x - w1.x) * px,
                          w1.y + (w2.y - w1.y) * px - py * height - droop,
                          w1.z + (w2.z - w1.z) * px);
                }
                p.pinned = (y == 0 && isTopPivot(x));
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
        SubLevelAccess subEnd = SableCompanion.INSTANCE.getContaining(level, endConnector);
        Pose3dc pose = sub != null ? sub.logicalPose() : null;
        // Each anchor uses its own sub-level's pose (flags are normally on one structure, but old saves may differ)
        Vec3 w1 = pose != null ? pose.transformPosition(c1) : c1;
        Vec3 w2 = subEnd != null ? subEnd.logicalPose().transformPosition(c2) : c2;

        if (!initialized) {
            init(w1, w2);
            return;
        }

        double span = w1.distanceTo(w2);
        double ropeSag = hasRope ? Math.max(0.12, span * 0.06) : 0.0;

        // Pin top corners, or every 5th pivot equally spaced along the rope path when a rope is attached
        for (int x = 0; x < gridW; x++) {
            if (!isTopPivot(x)) continue;
            double t = (double) x / (gridW - 1);
            double sagY = hasRope ? (4.0 * t * (1.0 - t) * ropeSag) : 0.0;
            Particle p = particles[x];
            p.px = p.x; p.py = p.y; p.pz = p.z;
            p.x = w1.x + (w2.x - w1.x) * t;
            p.y = w1.y + (w2.y - w1.y) * t - sagY;
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
            if (it == iterations / 2) {
                selfCollide();
                collideAll(level, sub, pose);
            }
        }
        selfCollide();
        collideAll(level, sub, pose);

        if (DEBUG && (++ticks % 100) == 0) {
            Particle mid = particles[gridW / 2 + (gridH - 1) * gridW];
            Particle top = particles[gridW / 2];
            int bad = 0;
            for (Particle p : particles) if (!Double.isFinite(p.x + p.y + p.z)) bad++;
            LOG.info("[create_bannerways] flag@{} onShip={} rope={} top=({},{},{}) bottom=({},{},{}) wind=({},{},{}) nonFinite={}",
                    startConnector.toShortString(), sub != null, hasRope,
                    f(top.x), f(top.y), f(top.z), f(mid.x), f(mid.y), f(mid.z),
                    f(wind.x), f(wind.y), f(wind.z), bad);
        }
    }

    /**
     * Where the pinned top-row particle {@code x} should be DRAWN this frame. The ship is rendered with its
     * interpolated render pose, while the simulation pins corners to the 20 Hz logical pose, so drawing the
     * pinned particles from the render pose keeps the cloth attached to the ship without judder.
     * Returns null when neither anchor is on a sub-level (the normal interpolation is already correct).
     */
    public Vec3 renderPinnedPos(int x, float partialTick) {
        ClientSubLevelAccess a = SableCompanion.INSTANCE.getContainingClient(startConnector);
        ClientSubLevelAccess b = SableCompanion.INSTANCE.getContainingClient(endConnector);
        if (a == null && b == null) return null;

        Vec3 c1 = Vec3.atCenterOf(startConnector);
        Vec3 c2 = Vec3.atCenterOf(endConnector);
        Vec3 w1 = a != null ? a.renderPose(partialTick).transformPosition(c1) : c1;
        Vec3 w2 = b != null ? b.renderPose(partialTick).transformPosition(c2) : c2;

        double t = (double) x / (gridW - 1);
        double ropeSag = hasRope ? Math.max(0.12, w1.distanceTo(w2) * 0.06) : 0.0;
        double sagY = hasRope ? (4.0 * t * (1.0 - t) * ropeSag) : 0.0;
        return new Vec3(w1.x + (w2.x - w1.x) * t,
                        w1.y + (w2.y - w1.y) * t - sagY,
                        w1.z + (w2.z - w1.z) * t);
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

    private void selfCollide() {
        Arrays.fill(hashHead, -1);
        int mask = hashTableSize - 1;
        double invCell = 1.0 / selfCellSize;

        for (int i = 0; i < particles.length; i++) {
            Particle p = particles[i];
            int cx = (int) Math.floor(p.x * invCell);
            int cy = (int) Math.floor(p.y * invCell);
            int cz = (int) Math.floor(p.z * invCell);
            int h = ((cx * 73856093) ^ (cy * 19349663) ^ (cz * 83492791)) & mask;
            hashNext[i] = hashHead[h];
            hashHead[h] = i;
        }

        double minDistSq = selfMinDist * selfMinDist;

        for (int i = 0; i < particles.length; i++) {
            Particle a = particles[i];
            int ax = i % gridW;
            int ay = i / gridW;

            int cx = (int) Math.floor(a.x * invCell);
            int cy = (int) Math.floor(a.y * invCell);
            int cz = (int) Math.floor(a.z * invCell);

            for (int ox = -1; ox <= 1; ox++) {
                for (int oy = -1; oy <= 1; oy++) {
                    for (int oz = -1; oz <= 1; oz++) {
                        int h = (((cx + ox) * 73856093) ^ ((cy + oy) * 19349663) ^ ((cz + oz) * 83492791)) & mask;
                        for (int j = hashHead[h]; j != -1; j = hashNext[j]) {
                            if (j <= i) continue;

                            int bx = j % gridW;
                            int by = j / gridW;
                            if (Math.abs(ax - bx) <= 1 && Math.abs(ay - by) <= 1) continue;

                            Particle b = particles[j];
                            double dx = b.x - a.x;
                            double dy = b.y - a.y;
                            double dz = b.z - a.z;
                            double d2 = dx * dx + dy * dy + dz * dz;
                            if (d2 >= minDistSq || d2 < 1.0E-12) continue;

                            double d = Math.sqrt(d2);
                            double push = (selfMinDist - d) / d;
                            double wa = a.pinned ? 0 : 1;
                            double wb = b.pinned ? 0 : 1;
                            double total = wa + wb;
                            if (total == 0) continue;

                            double fa = push * wa / total;
                            double fb = push * wb / total;
                            a.x -= dx * fa; a.y -= dy * fa; a.z -= dz * fa;
                            b.x += dx * fb; b.y += dy * fb; b.z += dz * fb;
                        }
                    }
                }
            }
        }
    }

    /**
     * Sable keeps a sub-level's blocks in the same Level, at plot coordinates, so the level that
     * holds a sub-level's blocks is simply the level we were given. (SubLevelAccess has no level
     * accessor, so the old reflection lookup never found one and ship collision was skipped.)
     */
    public static Level resolveSubLevel(Level level, SubLevelAccess sub) {
        return sub == null ? null : level;
    }

    private void collideAll(Level level, SubLevelAccess sub, Pose3dc pose) {
        Level shipLevel = resolveSubLevel(level, sub);
        for (Particle p : particles) {
            if (p.pinned) continue;

            double[] out = pushOut(level, p.x, p.y, p.z, pose == null);
            if (out != null) {
                p.x = out[0]; p.y = out[1]; p.z = out[2];
                p.px = p.x - (p.x - p.px) * 0.5;
                p.py = p.y - (p.y - p.py) * 0.5;
                p.pz = p.z - (p.z - p.pz) * 0.5;
            }

            if (pose != null && shipLevel != null) {
                Vec3 local = pose.transformPositionInverse(new Vec3(p.x, p.y, p.z));
                double[] pushed = pushOut(shipLevel, local.x, local.y, local.z, true);
                if (pushed != null) {
                    Vec3 world = pose.transformPosition(new Vec3(pushed[0], pushed[1], pushed[2]));
                    p.x = world.x; p.y = world.y; p.z = world.z;
                    p.px = p.x; p.py = p.y; p.pz = p.z;
                }
            }
        }
    }

    private double[] pushOut(Level level, double x, double y, double z, boolean skipConnectors) {
        BlockPos bp = BlockPos.containing(x, y, z);
        if (skipConnectors && (bp.equals(startConnector) || bp.equals(endConnector))) return null;

        BlockState state = level.getBlockState(bp);
        if (state.isAir()) return null;
        VoxelShape shape = state.getCollisionShape(level, bp);
        if (shape.isEmpty()) return null;

        double lx = x - bp.getX(), ly = y - bp.getY(), lz = z - bp.getZ();
        for (AABB box : shape.toAabbs()) {
            double minX = box.minX - THICKNESS, maxX = box.maxX + THICKNESS;
            double minY = box.minY - THICKNESS, maxY = box.maxY + THICKNESS;
            double minZ = box.minZ - THICKNESS, maxZ = box.maxZ + THICKNESS;
            if (lx <= minX || lx >= maxX || ly <= minY || ly >= maxY || lz <= minZ || lz >= maxZ) {
                continue;
            }
            double[] pen = {
                    lx - minX, maxX - lx,
                    ly - minY, maxY - ly,
                    lz - minZ, maxZ - lz
            };
            int best = 0;
            for (int i = 1; i < 6; i++) if (pen[i] < pen[best]) best = i;

            double[] res = {x, y, z};
            switch (best) {
                case 0 -> res[0] = bp.getX() + minX;
                case 1 -> res[0] = bp.getX() + maxX;
                case 2 -> res[1] = bp.getY() + minY;
                case 3 -> res[1] = bp.getY() + maxY;
                case 4 -> res[2] = bp.getZ() + minZ;
                default -> res[2] = bp.getZ() + maxZ;
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
