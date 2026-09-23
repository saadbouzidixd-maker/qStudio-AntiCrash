package studio.q.anticrash.api;

import java.util.List;
import java.util.Locale;

/**
 * A protection rule: identifier, threshold, severity and configured mitigation
 * actions. Rules are immutable snapshots loaded from rules.yml.
 */
public final class Rule {
    private final String id;
    private final String description;
    private final Severity severity;
    private final boolean enabled;
    private final double threshold;
    private final int cooldownMs;
    private final List<String> actions;

    private Rule(Builder b) {
        this.id = b.id;
        this.description = b.description;
        this.severity = b.severity;
        this.enabled = b.enabled;
        this.threshold = b.threshold;
        this.cooldownMs = b.cooldownMs;
        this.actions = b.actions;
    }

    public String id() {
        return id;
    }

    public String description() {
        return description;
    }

    public Severity severity() {
        return severity;
    }

    public boolean enabled() {
        return enabled;
    }

    /** Configured threshold; meaning depends on the module (rate, size, depth...). */
    public double threshold() {
        return threshold;
    }

    /** Minimum delay in ms between two alert/violation registrations for this rule. */
    public int cooldownMs() {
        return cooldownMs;
    }

    public List<String> actions() {
        return actions;
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    public static final class Builder {
        private final String id;
        private String description = "";
        private Severity severity = Severity.MEDIUM;
        private boolean enabled = true;
        private double threshold = 0;
        private int cooldownMs = 1000;
        private List<String> actions = List.of("LOG");

        private Builder(String id) {
            this.id = id == null ? "unknown" : id.toLowerCase(Locale.ROOT);
        }

        public Builder description(String description) {
            this.description = description == null ? "" : description;
            return this;
        }

        public Builder severity(Severity severity) {
            this.severity = severity == null ? Severity.MEDIUM : severity;
            return this;
        }

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder threshold(double threshold) {
            this.threshold = threshold;
            return this;
        }

        public Builder cooldownMs(int cooldownMs) {
            this.cooldownMs = cooldownMs;
            return this;
        }

        public Builder actions(List<String> actions) {
            this.actions = actions == null ? List.of("LOG") : List.copyOf(actions);
            return this;
        }

        public Rule build() {
            return new Rule(this);
        }
    }
}
