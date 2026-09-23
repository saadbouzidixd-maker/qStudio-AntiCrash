package studio.q.anticrash.violations;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Central violation tracker. Each (player, module) pair has an atomic level
 * that increases by the rule severity weight on each detection and decays on
 * the configured interval. Decay is applied lazily on access (no task per
 * player) plus a periodic sweep that removes players who left the server.
 */
public final class ViolationManager {
    private static final class Record {
        final AtomicLong level = new AtomicLong();
        final AtomicLong lastDecay = new AtomicLong();
        volatile long lastUpdate;
    }

    private final ConcurrentHashMap<UUID, Map<String, Record>> levels = new ConcurrentHashMap<>();
    private final long decayIntervalMillis;
    private final long decayAmount;
    private final LongSupplier clock;

    public ViolationManager(long decayIntervalMillis, long decayAmount, LongSupplier clock) {
        this.decayIntervalMillis = decayIntervalMillis;
        this.decayAmount = decayAmount;
        this.clock = clock;
    }

    /** Adds severity weight to the module's level for the player; returns the new level. */
    public long add(UUID playerId, String module, long weight) {
        Map<String, Record> byModule = levels.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>());
        Record r = byModule.computeIfAbsent(module.toLowerCase(Locale.ROOT), k -> new Record());
        long now = clock.getAsLong();
        r.lastUpdate = now;
        // A brand-new record must not decay immediately: anchor its decay clock.
        r.lastDecay.compareAndSet(0, now);
        long lvl = r.level.addAndGet(weight);
        lazyDecay(r, now);
        return r.level.get();
    }

    /** Current violation level without modifying it. */
    public long level(UUID playerId, String module) {
        Map<String, Record> byModule = levels.get(playerId);
        if (byModule == null) {
            return 0;
        }
        Record r = byModule.get(module.toLowerCase(Locale.ROOT));
        if (r == null) {
            return 0;
        }
        lazyDecay(r, clock.getAsLong());
        return r.level.get();
    }

    /** Total level across all modules for a player. */
    public long totalLevel(UUID playerId) {
        Map<String, Record> byModule = levels.get(playerId);
        if (byModule == null) {
            return 0;
        }
        long sum = 0;
        for (Record r : byModule.values()) {
            lazyDecay(r, clock.getAsLong());
            sum += r.level.get();
        }
        return sum;
    }

    /** Copies the current snapshot of module -> level for display. */
    public Map<String, Long> snapshot(UUID playerId) {
        Map<String, Long> out = new ConcurrentHashMap<>();
        Map<String, Record> byModule = levels.get(playerId);
        if (byModule == null) {
            return out;
        }
        long now = clock.getAsLong();
        for (Map.Entry<String, Record> e : byModule.entrySet()) {
            lazyDecay(e.getValue(), now);
            long lvl = e.getValue().level.get();
            if (lvl > 0) {
                out.put(e.getKey(), lvl);
            }
        }
        return out;
    }

    public void reset(UUID playerId) {
        levels.remove(playerId);
    }

    public void resetAll() {
        levels.clear();
    }

    private void lazyDecay(Record r, long now) {
        long last = r.lastDecay.get();
        if (now - last >= decayIntervalMillis && r.level.get() > 0) {
            if (r.lastDecay.compareAndSet(last, now)) {
                long cur = r.level.get();
                long next = Math.max(0, cur - decayAmount);
                r.level.compareAndSet(cur, next);
            }
        }
    }

    /** Removes stale records for players who disconnected. */
    public int cleanup(long olderThanMillis) {
        int removed = 0;
        long now = clock.getAsLong();
        for (Map.Entry<UUID, Map<String, Record>> e : levels.entrySet()) {
            Map<String, Record> byModule = e.getValue();
            boolean anyRecent = false;
            for (Record r : byModule.values()) {
                if (now - r.lastUpdate < olderThanMillis) {
                    anyRecent = true;
                    break;
                }
            }
            if (!anyRecent) {
                if (levels.remove(e.getKey(), byModule)) {
                    removed++;
                }
            }
        }
        return removed;
    }

    public int trackedPlayers() {
        return levels.size();
    }
}
