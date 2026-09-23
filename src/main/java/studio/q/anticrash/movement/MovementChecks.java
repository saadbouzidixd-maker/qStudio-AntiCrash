package studio.q.anticrash.movement;

/**
 * Stateless movement validation helpers. Pure functions on doubles/floats so
 * they can be unit tested without a server.
 */
public final class MovementChecks {
    private MovementChecks() {
    }

    /** True when all coordinates are finite (not NaN/Infinite). */
    public static boolean isFinite(double x, double y, double z) {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
    }

    /** True when yaw/pitch are finite and within Minecraft's float ranges. */
    public static boolean isFiniteRotation(float yaw, float pitch) {
        return Float.isFinite(yaw) && Float.isFinite(pitch)
                && Math.abs(pitch) <= 90.0f + 0.01f
                && Math.abs(yaw) <= 3600.0f; // extreme-but-finite guard; vanilla normalizes yaw
    }

    /** Absolute value of the largest coordinate component. */
    public static double maxAbs(double x, double y, double z) {
        return Math.max(Math.abs(x), Math.max(Math.abs(y), Math.abs(z)));
    }

    /** Squared distance between two points. */
    public static double distanceSq(double x1, double y1, double z1, double x2, double y2, double z2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double dz = z2 - z1;
        return dx * dx + dy * dy + dz * dz;
    }

    /** Human-readable description of which component is invalid. */
    public static String describeInvalid(double x, double y, double z) {
        if (!Double.isFinite(x)) {
            return "x=" + x;
        }
        if (!Double.isFinite(y)) {
            return "y=" + y;
        }
        return "z=" + z;
    }
}
