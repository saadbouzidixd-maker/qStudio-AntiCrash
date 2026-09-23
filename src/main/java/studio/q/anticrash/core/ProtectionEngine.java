package studio.q.anticrash.core;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import studio.q.anticrash.alerts.AlertService;
import studio.q.anticrash.api.Detection;
import studio.q.anticrash.api.Rule;
import studio.q.anticrash.config.ConfigManager;
import studio.q.anticrash.config.RuleEngine;
import studio.q.anticrash.exemptions.ExemptionService;
import studio.q.anticrash.logs.IncidentLogger;
import studio.q.anticrash.mitigation.Action;
import studio.q.anticrash.mitigation.ActionPlan;
import studio.q.anticrash.violations.ViolationManager;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Central protection engine. Receives {@link Detection}s from modules, checks
 * rules, exemptions and cooldowns, tracks violations, computes the mitigation
 * {@link ActionPlan} for the calling module, raises alerts and writes
 * incident records.
 *
 * Thread safety: this class is called from netty threads (packet layer) and
 * from the main thread (Bukkit events). All mutable state is concurrent or
 * atomic. Alerting and file logging are handed off to their own components.
 */
public final class ProtectionEngine {
    private final ConfigManager config;
    private final RuleEngine rules;
    private final ExemptionService exemptions;
    private final ViolationManager violations;
    private final AlertService alerts;
    private final IncidentLogger incidents;
    private final SafeMode safeMode;
    private final ModuleRegistry modules;
    private final MainThread mainThread;

    /** Per (ruleId, playerId) alert cooldown so incidents are not spammed. */
    private final ConcurrentHashMap<Long, Long> lastHandling = new ConcurrentHashMap<>();
    private final AtomicLong incidentCount = new AtomicLong();

    public ProtectionEngine(ConfigManager config, RuleEngine rules, ExemptionService exemptions,
                            ViolationManager violations, AlertService alerts, IncidentLogger incidents,
                            SafeMode safeMode, ModuleRegistry modules, MainThread mainThread) {
        this.config = config;
        this.rules = rules;
        this.exemptions = exemptions;
        this.violations = violations;
        this.alerts = alerts;
        this.incidents = incidents;
        this.safeMode = safeMode;
        this.modules = modules;
        this.mainThread = mainThread;
    }

    /** Fast-path check used by hot loops: is the player fully exempt? */
    public boolean isExempt(UUID playerId) {
        Player p = playerId == null ? null : Bukkit.getPlayer(playerId);
        return p == null || exemptions.isExempt(p);
    }

    /**
     * Handles a detection end-to-end and returns the plan the calling module
     * must enforce locally.
     */
    public ActionPlan handle(Detection detection) {
        Rule rule = rules.rule(detection.ruleId());
        if (!rule.enabled()) {
            return ActionPlan.none();
        }
        UUID pid = detection.playerId();
        if (pid != null) {
            Player p = Bukkit.getPlayer(pid);
            if (p == null || exemptions.isExempt(p)) {
                return ActionPlan.none();
            }
        }
        String worldName = detection.world();
        if (!worldName.isEmpty() && exemptions.isWorldExempt(worldName)) {
            return ActionPlan.none();
        }

        long now = System.currentTimeMillis();
        if (!passCooldown(rule, pid, now)) {
            // Already handling recently; apply the same plan without re-alerting.
            Set<Action> actions = Action.parseAll(rule.actions());
            return ActionPlan.of(actions);
        }

        // Safe Mode doubles the effective weight of every detection.
        long weight = rule.severity().weight();
        if (safeMode.isActive()) {
            weight *= 2;
        }
        long level = pid == null ? 0 : violations.add(pid, detection.module(), weight);

        Set<Action> actions = Action.parseAll(rule.actions());
        // In Safe Mode, escalate: pure-LOG rules also alert, KICK becomes DISCONNECT.
        if (safeMode.isActive()) {
            if (actions.contains(Action.KICK)) {
                actions = withEscalated(actions, Action.DISCONNECT);
            }
            if (actions.contains(Action.LOG) && !actions.contains(Action.ALERT)) {
                actions = withEscalated(actions, Action.ALERT);
            }
        }

        ActionPlan plan = ActionPlan.of(actions);
        incidentCount.incrementAndGet();
        incidents.logIncident(detection, actions.toString(), level);
        if (actions.contains(Action.ALERT) || plan.shouldDisconnect()) {
            alerts.notifyAlert(detection, plan, level);
        }
        if (plan.shouldDisconnect() && pid != null) {
            disconnect(pid, detection);
        }
        return plan;
    }

    private boolean passCooldown(Rule rule, UUID pid, long now) {
        long key = cooldownKey(rule.id(), pid);
        Long last = lastHandling.get(key);
        if (last != null && now - last < rule.cooldownMs()) {
            return false;
        }
        lastHandling.put(key, now);
        if (lastHandling.size() > 4096) {
            lastHandling.entrySet().removeIf(e -> now - e.getValue() > 300_000);
        }
        return true;
    }

    private long cooldownKey(String ruleId, UUID pid) {
        int h = ruleId.hashCode();
        long p = pid == null ? 0L : pid.getLeastSignificantBits();
        return ((long) h << 32) | (p & 0xFFFFFFFFL);
    }

    private Set<Action> withEscalated(Set<Action> original, Action extra) {
        Set<Action> copy = new java.util.HashSet<>(original);
        copy.add(extra);
        return Set.copyOf(copy);
    }

    private void disconnect(UUID pid, Detection d) {
        String message = config.msg("protection.disconnect-reason",
                "&cqStudio AntiCrash: blocked suspicious activity").replace("{module}", d.module());
        mainThread.run(() -> {
            Player p = Bukkit.getPlayer(pid);
            if (p != null && p.isOnline()) {
                p.kick(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                        .legacyAmpersand().deserialize(message));
            }
        });
    }

    public ViolationManager violations() {
        return violations;
    }

    public ExemptionService exemptions() {
        return exemptions;
    }

    public RuleEngine rules() {
        return rules;
    }

    public SafeMode safeMode() {
        return safeMode;
    }

    public ModuleRegistry modules() {
        return modules;
    }

    public AlertService alerts() {
        return alerts;
    }

    public MainThread mainThread() {
        return mainThread;
    }

    public long incidentCount() {
        return incidentCount.get();
    }

    public Map<String, Long> snapshot(UUID pid) {
        return violations.snapshot(pid);
    }
}
