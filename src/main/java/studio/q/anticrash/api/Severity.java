package studio.q.anticrash.api;

/**
 * Severity of a protection rule. Used for violation weighting, alert formatting
 * and incident records.
 */
public enum Severity {
    LOW(1),
    MEDIUM(2),
    HIGH(4),
    CRITICAL(8);

    private final int weight;

    Severity(int weight) {
        this.weight = weight;
    }

    public int weight() {
        return weight;
    }

    public static Severity parse(String name, Severity fallback) {
        if (name == null) {
            return fallback;
        }
        try {
            return valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }
}
