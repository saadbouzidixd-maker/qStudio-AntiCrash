package studio.q.anticrash.monitoring;

import org.bukkit.Bukkit;

import studio.q.anticrash.alerts.AlertService;
import studio.q.anticrash.api.Detection;
import studio.q.anticrash.api.Severity;
import studio.q.anticrash.core.MainThread;
import studio.q.anticrash.core.ProtectionEngine;
import studio.q.anticrash.core.SafeMode;
import studio.q.anticrash.mitigation.ActionPlan;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Monitors JVM heap usage and activates emergency protection (Safe Mode) at
 * critical levels. It never calls System.gc() as a "fix"; forcing GC from a
 * monitoring loop causes multi-second pauses and hides the real problem.
 */
public final class MemoryGuard {
    private final ProtectionEngine engine;
    private final AlertService alerts;
    private final SafeMode safeMode;
    private final MainThread mainThread;
    private final AtomicLong criticalCount = new AtomicLong();
    private volatile boolean criticalAlerted;

    public MemoryGuard(ProtectionEngine engine, AlertService alerts, SafeMode safeMode, MainThread mainThread) {
        this.engine = engine;
        this.alerts = alerts;
        this.safeMode = safeMode;
        this.mainThread = mainThread;
    }

    /** Called from the periodic health task (async). */
    public void check(double heapPercent) {
        double warning = engine.rules().rule("memory-warning").threshold() > 0
                ? engine.rules().rule("memory-warning").threshold()
                : 80.0;
        double critical = engine.rules().rule("memory-pressure").threshold() > 0
                ? engine.rules().rule("memory-pressure").threshold()
                : 90.0;
        if (heapPercent >= critical) {
            criticalCount.incrementAndGet();
            if (!criticalAlerted) {
                criticalAlerted = true;
                Detection d = Detection.builder(null, "server", "MemoryGuard", "memory-pressure")
                        .detail("Heap usage above critical threshold")
                        .actual(String.format(Locale.ROOT, "%.1f%%", heapPercent))
                        .limit(String.format(Locale.ROOT, "critical=%.1f%%", critical))
                        .severity(Severity.CRITICAL)
                        .build();
                engine.handle(d);
                if (!safeMode.isActive()) {
                    safeMode.setActive(true, "memory");
                    mainThread.run(() -> alerts.sendRaw(Bukkit.getConsoleSender(),
                            "&b[qStudio AntiCrash] &cHeap at " + String.format(Locale.ROOT, "%.1f%%", heapPercent)
                                    + " - Safe Mode ON. Memory-heavy checks reduced."));
                }
            }
        } else if (heapPercent < warning) {
            criticalAlerted = false;
            if (safeMode.isActive() && "memory".equals(safeMode.reason())) {
                safeMode.setActive(false, "");
            }
        }
    }

    public long criticalCount() {
        return criticalCount.get();
    }
}
