package studio.q.anticrash.core.ratelimit;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sliding-window counter for suspicious-action cooldowns (e.g. reconnect spam).
 * Tracks per-key counts within a fixed window without any per-event allocation
 * beyond the map entry itself. Entries expire via periodic cleanup.
 */
public final class WindowCounter {
    private static final class Entry {
        final AtomicLong count = new AtomicLong();
        volatile long windowStart;
    }

    private final ConcurrentHashMap<Long, Entry> entries = new ConcurrentHashMap<>();
    private final long windowMillis;
    private final long expireMillis;

    public WindowCounter(long windowMillis, long expireMillis) {
        this.windowMillis = windowMillis;
        this.expireMillis = Math.max(expireMillis, windowMillis * 4);
    }

    /** Increments the counter and returns the count within the current window. */
    public long increment(long key, long nowMillis) {
        Entry e = entries.computeIfAbsent(key, k -> new Entry());
        long start = e.windowStart;
        if (nowMillis - start >= windowMillis) {
            e.count.set(0);
            e.windowStart = nowMillis;
        }
        return e.count.incrementAndGet();
    }

    /** Current window count without incrementing. */
    public long current(long key, long nowMillis) {
        Entry e = entries.get(key);
        if (e == null) {
            return 0;
        }
        if (nowMillis - e.windowStart >= windowMillis) {
            return 0;
        }
        return e.count.get();
    }

    public int cleanup(long nowMillis) {
        int removed = 0;
        for (Map.Entry<Long, Entry> e : entries.entrySet()) {
            if (nowMillis - e.getValue().windowStart > expireMillis) {
                if (entries.remove(e.getKey(), e.getValue())) {
                    removed++;
                }
            }
        }
        return removed;
    }

    public int size() {
        return entries.size();
    }
}
