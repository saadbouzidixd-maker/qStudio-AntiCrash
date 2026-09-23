package studio.q.anticrash.core.ratelimit;

/**
 * Single-threaded fixed-window rate meter for main-thread event storms
 * (redstone, physics, chunk loads). Not thread-safe by design: the events it
 * meters only fire on the main thread, so no synchronization is needed.
 */
public final class RateMeter {
    private final long windowMillis;
    private long windowStart;
    private long count;
    private long total;

    public RateMeter(long windowMillis) {
        this.windowMillis = windowMillis;
    }

    /** Advances the window if needed and increments the counter. */
    public long increment(long nowMillis) {
        if (nowMillis - windowStart >= windowMillis) {
            windowStart = nowMillis;
            count = 0;
        }
        count++;
        total++;
        return count;
    }

    /** Current window count without incrementing. */
    public long current(long nowMillis) {
        if (nowMillis - windowStart >= windowMillis) {
            return 0;
        }
        return count;
    }

    public long total() {
        return total;
    }

    public void reset() {
        windowStart = 0;
        count = 0;
    }
}
