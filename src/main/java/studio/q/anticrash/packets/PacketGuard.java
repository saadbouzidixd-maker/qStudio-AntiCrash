package studio.q.anticrash.packets;

import studio.q.anticrash.core.ratelimit.RateLimiter;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared packet-guard state: per-category rate limiting, global packet rate,
 * and text/payload size checks. Used by both packet layers (PacketEvents and
 * ProtocolLib) so behavior is identical whichever one is active.
 *
 * One token-bucket RateLimiter exists per category (11 total); keys inside a
 * limiter separate players. configure() atomically swaps the limiter array on
 * reload; in-flight buckets are discarded, which is safe (players get a fresh
 * bucket, worst case one extra packet).
 */
public final class PacketGuard {
    /** Verdict returned to listeners. */
    public record Verdict(boolean blocked, boolean report, String actual, String limit) {
        public static final Verdict OK = new Verdict(false, false, "", "");
    }

    private final AtomicLong totalPackets = new AtomicLong();
    private final AtomicLong blockedPackets = new AtomicLong();
    private volatile RateLimiter[] categoryLimiters = emptyLimiters();
    private volatile RateLimiter globalLimiter = new RateLimiter(1000, 1500, 120_000);
    private volatile boolean enabled = true;

    public PacketGuard() {
    }

    private static RateLimiter[] emptyLimiters() {
        RateLimiter[] arr = new RateLimiter[PacketCategory.values().length];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = null;
        }
        return arr;
    }

    /** Applies configured limits; called on load and reload. */
    public void configure(Map<String, Integer> perSecond, Map<String, Integer> bursts, int globalPerSecond) {
        RateLimiter[] arr = new RateLimiter[PacketCategory.values().length];
        for (PacketCategory cat : PacketCategory.values()) {
            int ps = perSecond.getOrDefault(cat.name().toLowerCase(Locale.ROOT), cat.defaultPerSecond());
            int b = bursts.getOrDefault(cat.name().toLowerCase(Locale.ROOT), cat.defaultBurst());
            if (ps <= 0) {
                arr[cat.ordinal()] = null; // category disabled
            } else {
                arr[cat.ordinal()] = new RateLimiter(ps, Math.max(b, 1), 120_000);
            }
        }
        this.categoryLimiters = arr;
        if (globalPerSecond > 0) {
            this.globalLimiter = new RateLimiter(globalPerSecond, Math.max(globalPerSecond / 2, 1), 120_000);
        }
    }

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean value) {
        this.enabled = value;
    }

    public void recordPacket(String typeName) {
        totalPackets.incrementAndGet();
    }

    public long totalPackets() {
        return totalPackets.get();
    }

    public long blockedPackets() {
        return blockedPackets.get();
    }

    /** Per-category rate check; returns a verdict with report flag. */
    public Verdict checkRate(UUID pid, String name, PacketCategory category, long now) {
        RateLimiter limiter = categoryLimiters[category.ordinal()];
        if (limiter == null) {
            return Verdict.OK;
        }
        long key = RateLimiter.key(pid.getMostSignificantBits(),
                (int) pid.getLeastSignificantBits(), 0);
        if (limiter.tryConsume(key, now)) {
            return Verdict.OK;
        }
        blockedPackets.incrementAndGet();
        String actual = String.format(Locale.ROOT, "%s rate exceeded", category.name().toLowerCase(Locale.ROOT));
        String limit = String.format(Locale.ROOT, "%d/s burst %d",
                (int) limiter.perSecond(), (int) limiter.burst());
        return new Verdict(true, true, actual, limit);
    }

    /** Global per-player packet rate check (all categories combined). */
    public Verdict checkGlobalRate(UUID pid, String name, long now) {
        RateLimiter limiter = globalLimiter;
        if (limiter == null) {
            return Verdict.OK;
        }
        long key = RateLimiter.key(pid.getMostSignificantBits(),
                (int) pid.getLeastSignificantBits(), 0);
        if (limiter.tryConsume(key, now)) {
            return Verdict.OK;
        }
        blockedPackets.incrementAndGet();
        return new Verdict(true, true,
                "global packet rate exceeded",
                String.format(Locale.ROOT, "%d/s", (int) limiter.perSecond()));
    }

    /** Text size checks (chat, commands, tab). */
    public Verdict checkText(UUID pid, String name, String kind, String text, int maxLen) {
        if (maxLen > 0 && text.length() > maxLen) {
            return new Verdict(true, true,
                    kind + " length=" + text.length(),
                    "max-" + kind + "-length=" + maxLen);
        }
        return Verdict.OK;
    }

    /** Plugin-message payload size check plus channel denylist. */
    public Verdict checkPayload(UUID pid, String name, String channel, int length, int max, boolean denied) {
        if (max > 0 && length > max) {
            return new Verdict(true, true,
                    "channel=" + channel + " bytes=" + length,
                    "max-payload-bytes=" + max);
        }
        if (denied) {
            return new Verdict(true, true, "channel=" + channel, "denied-channel");
        }
        return Verdict.OK;
    }

    /** Periodic maintenance: drop expired buckets. Called from the slow task. */
    public void cleanup(long now) {
        for (RateLimiter r : categoryLimiters) {
            if (r != null) {
                r.cleanup(now);
            }
        }
        RateLimiter g = globalLimiter;
        if (g != null) {
            g.cleanup(now);
        }
    }

    public int activeBuckets() {
        int n = 0;
        for (RateLimiter r : categoryLimiters) {
            if (r != null) {
                n += r.size();
            }
        }
        return n;
    }
}
