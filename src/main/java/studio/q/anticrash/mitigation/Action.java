package studio.q.anticrash.mitigation;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Mitigation actions supported by the engine. Actions are configured per rule
 * in rules.yml. Engine-side actions (LOG/WARN/ALERT/DISCONNECT/QUARANTINE)
 * are executed by the protection engine; module-side actions (CANCEL,
 * RATE_LIMIT, REMOVE_ENTITY, ...) are applied by the module that owns the
 * underlying event or packet, using the returned {@link ActionPlan}.
 */
public enum Action {
    LOG,
    WARN,
    ALERT,
    CANCEL,
    RATE_LIMIT,
    TEMP_BLOCK,
    DISCONNECT,
    KICK,
    QUARANTINE,
    DISABLE_FEATURE,
    REMOVE_ENTITY,
    ROLLBACK_ACTION;

    public static Action parse(String name) {
        if (name == null) {
            return LOG;
        }
        try {
            return valueOf(name.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException ex) {
            return LOG;
        }
    }

    public static Set<Action> parseAll(List<String> names) {
        java.util.EnumSet<Action> set = java.util.EnumSet.noneOf(Action.class);
        if (names != null) {
            for (String n : names) {
                set.add(parse(n));
            }
        }
        if (set.isEmpty()) {
            set.add(LOG);
        }
        return java.util.Collections.unmodifiableSet(set);
    }
}
