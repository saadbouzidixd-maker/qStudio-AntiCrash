package studio.q.anticrash.api;

import java.util.UUID;

/**
 * A detection report produced by a protection module when it observes
 * behavior that exceeds a configured threshold or matches a dangerous pattern.
 * All fields are factual values observed at detection time.
 */
public final class Detection {
    private final UUID playerId;
    private final String playerName;
    private final String module;
    private final String ruleId;
    private final String detail;
    private final String actual;
    private final String limit;
    private final Severity severity;
    private final String world;

    private Detection(Builder b) {
        this.playerId = b.playerId;
        this.playerName = b.playerName;
        this.module = b.module;
        this.ruleId = b.ruleId;
        this.detail = b.detail;
        this.actual = b.actual;
        this.limit = b.limit;
        this.severity = b.severity;
        this.world = b.world;
    }

    /** World name when the module runs on the main thread; empty otherwise. */
    public String world() {
        return world;
    }

    public UUID playerId() {
        return playerId;
    }

    public String playerName() {
        return playerName;
    }

    public String module() {
        return module;
    }

    public String ruleId() {
        return ruleId;
    }

    /** Human-readable description of the detected behavior. */
    public String detail() {
        return detail;
    }

    /** Actual observed value, e.g. "482/s". Never fabricated. */
    public String actual() {
        return actual;
    }

    /** Configured limit that was exceeded, e.g. "250/s". */
    public String limit() {
        return limit;
    }

    public Severity severity() {
        return severity;
    }

    public static Builder builder(UUID playerId, String playerName, String module, String ruleId) {
        return new Builder(playerId, playerName, module, ruleId);
    }

    public static final class Builder {
        private final UUID playerId;
        private final String playerName;
        private final String module;
        private final String ruleId;
        private String detail = "";
        private String actual = "";
        private String limit = "";
        private Severity severity = Severity.MEDIUM;
        private String world = "";

        private Builder(UUID playerId, String playerName, String module, String ruleId) {
            this.playerId = playerId;
            this.playerName = playerName == null ? "unknown" : playerName;
            this.module = module;
            this.ruleId = ruleId;
        }

        public Builder detail(String detail) {
            this.detail = detail == null ? "" : detail;
            return this;
        }

        public Builder actual(String actual) {
            this.actual = actual == null ? "" : actual;
            return this;
        }

        public Builder limit(String limit) {
            this.limit = limit == null ? "" : limit;
            return this;
        }

        public Builder severity(Severity severity) {
            this.severity = severity == null ? Severity.MEDIUM : severity;
            return this;
        }

        public Builder world(String world) {
            this.world = world == null ? "" : world;
            return this;
        }

        public Detection build() {
            return new Detection(this);
        }
    }
}
