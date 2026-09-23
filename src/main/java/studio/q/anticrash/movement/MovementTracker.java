package studio.q.anticrash.movement;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player movement tracker. Maintains the last accepted position per player
 * and classifies deltas. The server teleports players and moves them via
 * vehicles/velocity/ender pearls, so a single large delta is never flagged on
 * its own (that would break teleports); only two consecutive impossible
 * deltas are flagged. Rotation spam is measured as accumulated degrees per
 * one-second window.
 */
public final class MovementTracker {
    /** Max blocks per movement packet before a delta is considered impossible. */
    public static final double MAX_DELTA_PER_PACKET = 100.0;
    /** Max accumulated rotation change per second (degrees) before spam. */
    public static final double MAX_ROTATION_PER_SECOND = 3600.0 * 4.0;

    private static final class State {
        double prevX;
        double prevY;
        double prevZ;
        float lastYaw;
        float lastPitch;
        long lastTime;
        double rotationAccum;
        long rotationWindowStart;
        boolean lastWasLarge;
        boolean initialized;
    }

    private final ConcurrentHashMap<UUID, State> states = new ConcurrentHashMap<>();

    public record Sample(double x, double y, double z, float yaw, float pitch, long timeMillis) {
    }

    public record Analysis(boolean impossibleDelta, boolean rotationSpam, String describe, String limit) {
        public static final Analysis NONE = new Analysis(false, false, "", "");
    }

    public Analysis observe(UUID playerId, Sample s) {
        State st = states.computeIfAbsent(playerId, k -> new State());
        Analysis result = Analysis.NONE;
        synchronized (st) {
            if (!st.initialized) {
                st.prevX = s.x();
                st.prevY = s.y();
                st.prevZ = s.z();
                st.lastYaw = s.yaw();
                st.lastPitch = s.pitch();
                st.lastTime = s.timeMillis();
                st.rotationWindowStart = s.timeMillis();
                st.initialized = true;
                return Analysis.NONE;
            }
            long dt = s.timeMillis() - st.lastTime;
            if (dt > 0) {
                double dist = Math.sqrt(MovementChecks.distanceSq(st.prevX, st.prevY, st.prevZ,
                        s.x(), s.y(), s.z()));
                if (dist > MAX_DELTA_PER_PACKET) {
                    if (st.lastWasLarge) {
                        st.lastWasLarge = false;
                        String actual = String.format(Locale.ROOT, "delta=%.1f blocks in %dms", dist, dt);
                        String limit = String.format(Locale.ROOT, "%.1f blocks/packet", MAX_DELTA_PER_PACKET);
                        result = new Analysis(true, false, actual, limit);
                    } else {
                        // Give one free large delta (teleports, pearls, velocity, plugins).
                        st.lastWasLarge = true;
                    }
                } else {
                    st.lastWasLarge = false;
                    float dyaw = Math.abs(s.yaw() - st.lastYaw);
                    float dpitch = Math.abs(s.pitch() - st.lastPitch);
                    st.rotationAccum += dyaw + dpitch;
                }
                if (s.timeMillis() - st.rotationWindowStart >= 1000) {
                    if (st.rotationAccum > MAX_ROTATION_PER_SECOND && !result.impossibleDelta()) {
                        String actual = String.format(Locale.ROOT, "%.0f deg/s", st.rotationAccum);
                        String limit = String.format(Locale.ROOT, "%.0f deg/s", MAX_ROTATION_PER_SECOND);
                        result = new Analysis(false, true, actual, limit);
                    }
                    st.rotationAccum = 0;
                    st.rotationWindowStart = s.timeMillis();
                }
            }
            st.prevX = s.x();
            st.prevY = s.y();
            st.prevZ = s.z();
            st.lastYaw = s.yaw();
            st.lastPitch = s.pitch();
            st.lastTime = s.timeMillis();
        }
        return result;
    }

    public void remove(UUID playerId) {
        states.remove(playerId);
    }

    public int tracked() {
        return states.size();
    }
}
