package studio.q.anticrash.monitoring;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.DoubleSupplier;

/**
 * Server health sampler: TPS, MSPT, heap, entities, chunks, players.
 *
 * Performance: one async sample task every N ticks (default 60 = 3s). It reads
 * only verified main-thread-safe metrics (Bukkit.getTPS/getTickTimes are
 * snapshot getters) and numeric counters. No world iteration happens here;
 * entity/chunk totals are sampled synchronously at a much lower cadence
 * (default every 60s) inside a short, O(worlds) loop over World#getEntities
 * size only when enabled in config.
 */
public final class HealthMonitor {
    private final JavaPlugin plugin;
    private final DoubleSupplier tpsSupplier;
    private volatile double lastTps = 20.0;
    private volatile double lastMspt = 0.0;
    private volatile double lastHeapPercent = 0.0;
    private volatile int lastEntities = 0;
    private volatile int lastChunks = 0;
    private volatile int lastPlayers = 0;
    private volatile boolean degraded = false;
    private final AtomicLong samples = new AtomicLong();

    public HealthMonitor(JavaPlugin plugin, DoubleSupplier tpsSupplier) {
        this.plugin = plugin;
        this.tpsSupplier = tpsSupplier;
    }

    public void sample() {
        samples.incrementAndGet();
        lastTps = tpsSupplier.getAsDouble();
        lastMspt = Bukkit.getAverageTickTime();
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        long max = rt.maxMemory();
        lastHeapPercent = max > 0 ? (used * 100.0) / max : 0.0;
        lastPlayers = Bukkit.getOnlinePlayers().size();
    }

    /** Slow synchronous sample of entity/chunk counts (config-gated). */
    public void sampleWorlds() {
        int entities = 0;
        int chunks = 0;
        for (org.bukkit.World w : Bukkit.getWorlds()) {
            entities += w.getEntities().size();
            chunks += w.getLoadedChunks().length;
        }
        lastEntities = entities;
        lastChunks = chunks;
    }

    public double tps() {
        return lastTps;
    }

    public double mspt() {
        return lastMspt;
    }

    public double heapPercent() {
        return lastHeapPercent;
    }

    public int entities() {
        return lastEntities;
    }

    public int chunks() {
        return lastChunks;
    }

    public int players() {
        return lastPlayers;
    }

    public boolean degraded() {
        return degraded;
    }

    public void setDegraded(boolean value) {
        this.degraded = value;
    }

    public long sampleCount() {
        return samples.get();
    }
}
