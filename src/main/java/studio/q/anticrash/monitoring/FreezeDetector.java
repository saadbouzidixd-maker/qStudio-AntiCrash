package studio.q.anticrash.monitoring;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import studio.q.anticrash.alerts.AlertService;
import studio.q.anticrash.api.Detection;
import studio.q.anticrash.api.Severity;
import studio.q.anticrash.core.MainThread;
import studio.q.anticrash.core.ProtectionEngine;
import studio.q.anticrash.core.SafeMode;
import studio.q.anticrash.mitigation.ActionPlan;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Detects TPS collapse and sustained low performance ("freeze-like"
 * conditions) using sampled TPS/MSPT and triggers graduated responses:
 * first an alert, then Safe Mode activation while the condition persists.
 *
 * The monitor never kicks players. It activates Safe Mode, which modules read
 * to tighten limits, and reports the worst offending observation so admins
 * can investigate. It deliberately avoids guessing a single "source" player,
 * because at this level the evidence is server-wide, not per-player.
 */
public final class FreezeDetector {
    private final JavaPlugin plugin;
    private final ProtectionEngine engine;
    private final AlertService alerts;
    private final HealthMonitor health;
    private final SafeMode safeMode;
    private final MainThread mainThread;
    private final AtomicLong triggerCount = new AtomicLong();
    private volatile long lowTpsSince;
    private volatile boolean alerted;

    public FreezeDetector(JavaPlugin plugin, ProtectionEngine engine, AlertService alerts,
                          HealthMonitor health, SafeMode safeMode, MainThread mainThread) {
        this.plugin = plugin;
        this.engine = engine;
        this.alerts = alerts;
        this.health = health;
        this.safeMode = safeMode;
        this.mainThread = mainThread;
    }

    /** Called from the periodic sampling task (async). */
    public void check() {
        double tps = health.tps();
        double mspt = health.mspt();
        double tpsThreshold = engine.rules().rule("tps-collapse").threshold() > 0
                ? engine.rules().rule("tps-collapse").threshold()
                : plugin.getConfig().getDouble("performance.min-tps", 12.0);
        double msptThreshold = plugin.getConfig().getDouble("performance.max-mspt", 150.0);
        int sustainTicks = plugin.getConfig().getInt("performance.sustain-samples", 5);

        boolean bad = tps > 0 && tps < tpsThreshold || mspt > msptThreshold;
        if (!bad) {
            lowTpsSince = 0;
            alerted = false;
            if (safeMode.isActive() && "performance".equals(safeMode.reason())) {
                safeMode.setActive(false, "");
            }
            return;
        }
        long now = System.currentTimeMillis();
        if (lowTpsSince == 0) {
            lowTpsSince = now;
            return;
        }
        long sustainedFor = now - lowTpsSince;
        long required = sustainTicks * 3000L; // sample cadence is ~3s
        if (sustainedFor < required) {
            return;
        }
        if (!alerted) {
            alerted = true;
            triggerCount.incrementAndGet();
            Detection d = Detection.builder(null, "server", "FreezeDetector", "tps-collapse")
                    .detail("Sustained low TPS or high MSPT for " + (sustainedFor / 1000) + "s")
                    .actual("tps=" + String.format(java.util.Locale.ROOT, "%.2f", tps)
                            + " mspt=" + String.format(java.util.Locale.ROOT, "%.1f", mspt))
                    .limit("min-tps=" + tpsThreshold + " max-mspt=" + msptThreshold)
                    .severity(Severity.HIGH)
                    .build();
            ActionPlan plan = engine.handle(d);
            if (plan.isEmpty()) {
                alerts.sendRaw(Bukkit.getConsoleSender(),
                        "&b[qStudio AntiCrash] &ePerformance degraded but tps-collapse rule is disabled.");
            }
        }
        if (!safeMode.isActive() && plugin.getConfig().getBoolean("safemode.auto-enable-on-degradation", true)) {
            safeMode.setActive(true, "performance");
            mainThread.run(() -> alerts.sendRaw(Bukkit.getConsoleSender(),
                    "&b[qStudio AntiCrash] &cSafe Mode ACTIVATED (reason: performance). "
                            + "Protections are now stricter. Use /qanticrash safemode to toggle."));
        }
    }

    public long triggerCount() {
        return triggerCount.get();
    }

    /** Manual check used by /qanticrash test. */
    public String describe() {
        return String.format(java.util.Locale.ROOT,
                "tps=%.2f mspt=%.1f heap=%.1f%% entities=%d chunks=%d players=%d safeMode=%s",
                health.tps(), health.mspt(), health.heapPercent(), health.entities(),
                health.chunks(), health.players(), safeMode.isActive());
    }

    public void reset() {
        lowTpsSince = 0;
        alerted = false;
    }

    /** Snapshot helper for diagnostics (per-player view is not applicable here). */
    public long snapshotLevel(UUID ignored) {
        return 0;
    }
}
