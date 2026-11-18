package com.simibubi.create_bannerways;

import com.mojang.logging.LogUtils;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.slf4j.Logger;

import java.lang.reflect.Method;

/**
 * Single place that answers "what is the wind at this world position?" Returns a wind VELOCITY in
 * blocks/tick, in world space.
 *
 * Reads Create: Atmospherics' wind through reflection, so it is an optional dependency:
 *   WindsSim.forLevel(level).sampleWind(level, x, y, z) -> org.joml.Vector3d
 * This is the same simulation that /windsim overrides and the anemometer read, so setting the wind to
 * "100 at 90" changes what comes out here.
 *
 * Without Atmospherics installed a gentle procedural breeze is used.
 */
public final class FlagWind {
    private static final Logger LOG = LogUtils.getLogger();

    private static final String SIM_CLASS = "com.enzonic.aeronauticswinds.WindsSim";

    /**
     * Atmospherics' units -> blocks/tick. If its wind is in m/s, 1 m = 1 block and 20 ticks = 1 s,
     * so 0.05. Tune this if flags look too stiff or too limp.
     */
    private static final double UNITS_TO_BLOCKS_PER_TICK = 0.05;

    private static boolean resolved = false;
    private static Method getOrNull;     // static WindsSim.getOrNull(Level) -> Simulation
    private static Method forLevel;      // static WindsSim.forLevel(Level)  -> Simulation
    private static Method sampleWind;    // Simulation.sampleWind(Level, double, double, double) -> Vector3d
    private static boolean loggedFailure = false;

    private FlagWind() {}

    public static Vec3 sample(Level level, Vec3 worldPos) {
        if (!resolved) resolve();
        if (sampleWind != null) {
            try {
                Object sim = getOrNull != null ? getOrNull.invoke(null, level) : null;
                if (sim == null && forLevel != null) sim = forLevel.invoke(null, level);
                if (sim != null) {
                    Vector3d w = (Vector3d) sampleWind.invoke(sim, level, worldPos.x, worldPos.y, worldPos.z);
                    if (w != null) {
                        return new Vec3(w.x, w.y, w.z).scale(UNITS_TO_BLOCKS_PER_TICK);
                    }
                }
            } catch (Throwable t) {
                if (!loggedFailure) {
                    loggedFailure = true;
                    LOG.warn("[create_bannerways] Atmospherics wind lookup failed; using ambient breeze", t);
                }
                sampleWind = null;
            }
        }
        double time = level.getGameTime() * 0.01;
        return new Vec3(0.04 * Math.sin(time), 0.0, 0.03 * Math.cos(time * 0.7));
    }

    private static void resolve() {
        resolved = true;
        try {
            Class<?> sims = Class.forName(SIM_CLASS);
            try { getOrNull = sims.getMethod("getOrNull", Level.class); } catch (NoSuchMethodException ignored) {}
            forLevel = sims.getMethod("forLevel", Level.class);
            Class<?> simClass = forLevel.getReturnType();
            sampleWind = simClass.getMethod("sampleWind", Level.class, double.class, double.class, double.class);
            LOG.info("[create_bannerways] Using Create: Atmospherics wind");
        } catch (ClassNotFoundException e) {
            LOG.info("[create_bannerways] Create: Atmospherics not present; using ambient breeze");
        } catch (Throwable t) {
            LOG.warn("[create_bannerways] Could not hook Create: Atmospherics wind; using ambient breeze", t);
        }
    }
}