package studio.q.anticrash.mitigation;

import java.util.EnumSet;
import java.util.Set;

/**
 * Immutable result handed back to the module that produced a detection so it
 * can enforce event/packet-level mitigation itself (cancel, drop, remove...).
 */
public final class ActionPlan {
    private static final ActionPlan NONE = new ActionPlan(EnumSet.noneOf(Action.class));

    private final Set<Action> actions;

    private ActionPlan(Set<Action> actions) {
        this.actions = actions;
    }

    public static ActionPlan none() {
        return NONE;
    }

    public static ActionPlan of(Set<Action> actions) {
        if (actions == null || actions.isEmpty()) {
            return NONE;
        }
        return new ActionPlan(EnumSet.copyOf(actions));
    }

    public Set<Action> actions() {
        return actions;
    }

    public boolean isEmpty() {
        return actions.isEmpty();
    }

    public boolean shouldCancel() {
        return actions.contains(Action.CANCEL);
    }

    public boolean shouldRateLimit() {
        return actions.contains(Action.RATE_LIMIT);
    }

    public boolean shouldDisconnect() {
        return actions.contains(Action.DISCONNECT) || actions.contains(Action.KICK);
    }

    public boolean shouldQuarantine() {
        return actions.contains(Action.QUARANTINE) || actions.contains(Action.TEMP_BLOCK);
    }

    public boolean shouldRemoveEntity() {
        return actions.contains(Action.REMOVE_ENTITY);
    }

    public boolean shouldLog() {
        return actions.contains(Action.LOG) || actions.contains(Action.WARN);
    }
}
