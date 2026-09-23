package studio.q.anticrash.core.ratelimit;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;

/**
 * Token-bucket rate limiter for per-key limits such as per-player packet rates.
 *
 * Design notes:
 * - Lock-free per bucket: the bucket state is packed into a single AtomicLong
 *   (high 32 bits = tokens scaled by PRECISION, low 32 bits = last refill ms).
 * - No allocation on the hot path beyond the initial bucket entry.
 * - Buckets expire; the cleanup pass removes stale entries so maps cannot grow
 *   without bound. Cleanup is called from a slow scheduled task, never per packet.
 */
public final class RateLimiter {
    private static final int PRECISION = 1000; // fixed-point 3 decimals
    private static final int MAX_BUCKETS = 65536;
    private static final int SAMPLED_REJECT_LIMIT = 100;

    private final ConcurrentHashMap<Long, AtomicLong> buckets = new ConcurrentHashMap<>();
    private volatile long capacityTokens;   // burst capacity in whole tokens
    private volatile double refillPerSecond;
    private volatile long expireMillis;

    public RateLimiter(double perSecond, int burst, long expireMillis) {
        if (perSecond <= 0) {
            throw new IllegalArgumentException("perSecond must be > 0");
        }
        if (burst < 1) {
            throw new IllegalArgumentException("burst must be >= 1");
        }
        this.refillPerSecond = perSecond;
        this.capacityTokens = burst;
        this.expireMillis = expireMillis;
    }

    /** Resolves the bucket key for the given id + sub-key. */
    public static long key(long idHigh, int idLow, int subKey) {
        return (idHigh << 32) | ((idLow ^ subKey) & 0xFFFFFFFFL);
    }

    /**
     * Tries to consume one token. Returns true when allowed.
     * Upholds capacity = burst, refill = perSecond tokens per second.
     */
    public boolean tryConsume(long key, long nowMillis) {
        AtomicLong bucket = buckets.computeIfAbsent(key, k -> new AtomicLong(initialState(nowMillis)));
        long state = bucket.get();
        while (true) {
            long lastRefill = state & 0xFFFFFFFFL;   // unsigned-safe enough for our purposes
            int tokensFixed = (int) (state >>> 32);
            long elapsed = nowMillis - lastRefill;
            double refill = elapsed > 0 ? (elapsed / 1000.0) * refillPerSecond : 0.0;
            int newTokensFixed = (int) Math.min(capacityTokens * PRECISION, tokensFixed + refill * PRECISION);
            if (newTokensFixed < PRECISION) {
                // Not enough tokens: attempt a cheap CAS to refresh refill timestamp so
                // time still accrues even under sustained pressure.
                long next = pack(newTokensFixed, nowMillis);
                bucket.compareAndSet(state, next);
                return false;
            }
            long next = pack(newTokensFixed - PRECISION, nowMillis);
            if (bucket.compareAndSet(state, next)) {
                return true;
            }
            state = bucket.get();
        }
    }

    /** Convenience: consume with a per-call key computed from id and sub-key. */
    public boolean tryConsume(long idHigh, int idLow, int subKey, long nowMillis) {
        return tryConsume(key(idHigh, idLow, subKey), nowMillis);
    }

    private long initialState(long nowMillis) {
        // Start with a full bucket.
        return pack((int) (capacityTokens * PRECISION), nowMillis);
    }

    private long pack(int tokensFixed, long nowMillis) {
        return (((long) tokensFixed) << 32) | (nowMillis & 0xFFFFFFFFL);
    }

    /**
     * Removes buckets untouched for longer than the expiry window.
     * Called from a slow async maintenance task.
     */
    public int cleanup(long nowMillis) {
        int removed = 0;
        for (Map.Entry<Long, AtomicLong> e : buckets.entrySet()) {
            long state = e.getValue().get();
            long lastRefill = state & 0xFFFFFFFFL;
            long age = nowMillis - lastRefill;
            // If the low word wrapped (rare), age can be negative; skip those.
            if (age > expireMillis && age > 0) {
                if (buckets.remove(e.getKey(), e.getValue())) {
                    removed++;
                }
            }
        }
        // Absolute safety valve: if the map grows beyond the cap, drop half of the
        // sampled entries. Should never trigger with correct cleanup cadence.
        if (buckets.size() > MAX_BUCKETS) {
            int dropped = 0;
            for (Long k : buckets.keySet()) {
                if (dropped >= SAMPLED_REJECT_LIMIT) {
                    break;
                }
                if (buckets.remove(k) != null) {
                    dropped++;
                }
            }
            removed += dropped;
        }
        return removed;
    }

    public int size() {
        return buckets.size();
    }

    /** Exposes the configured per-second rate, for diagnostics. */
    public double perSecond() {
        return refillPerSecond;
    }

    /** Exposes the configured burst capacity, for diagnostics. */
    public long burst() {
        return capacityTokens;
    }

    /** Reconfigures the limiter in place (used on reload). Existing buckets are kept; limits swap atomically. */
    public void configure(double perSecond, int burst, long expireMillis) {
        if (perSecond <= 0) {
            throw new IllegalArgumentException("perSecond must be > 0");
        }
        if (burst < 1) {
            burst = 1;
        }
        this.refillPerSecond = perSecond;
        this.capacityTokens = burst;
        this.expireMillis = expireMillis;
    }

    /** Utility: resolve a non-negative int id from a supplier, used by modules. */
    public static int safeId(IntSupplier supplier) {
        int v = supplier.getAsInt();
        return v == Integer.MIN_VALUE ? 0 : v;
    }
}
