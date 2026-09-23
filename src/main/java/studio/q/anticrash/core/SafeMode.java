package studio.q.anticrash.core;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Safe Mode state. When active, the engine escalates mitigations faster and
 * modules tighten their limits. Always reversible via /qanticrash safemode or
 * automatic deactivation once the server recovers.
 */
public final class SafeMode {
    private final AtomicBoolean active = new AtomicBoolean(false);
    private volatile String reason = "";
    private volatile long since;

    public boolean isActive() {
        return active.get();
    }

    public boolean setActive(boolean value, String reason) {
        boolean changed = active.compareAndSet(!value, value);
        if (changed) {
            this.reason = reason == null ? "" : reason;
            this.since = System.currentTimeMillis();
        }
        return changed;
    }

    public String reason() {
        return reason;
    }

    public long sinceMillis() {
        return since;
    }
}
