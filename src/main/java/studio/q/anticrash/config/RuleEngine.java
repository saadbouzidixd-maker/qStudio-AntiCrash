package studio.q.anticrash.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import studio.q.anticrash.api.Rule;
import studio.q.anticrash.api.Severity;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

/**
 * Loads protection rules from rules.yml. Each rule under the top-level
 * "rules" key has: enabled, severity, threshold (optional), cooldown-ms and
 * actions (list of mitigation action names).
 */
public final class RuleEngine {
    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private final Map<String, Rule> rules = new HashMap<>();

    public RuleEngine(JavaPlugin plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
    }

    public void load() {
        rules.clear();
        ConfigurationSection root = configManager.rules().getConfigurationSection("rules");
        if (root == null) {
            plugin.getLogger().warning("rules.yml has no 'rules' section; all modules will use built-in defaults.");
            return;
        }
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) {
                continue;
            }
            Severity severity = Severity.parse(s.getString("severity"), Severity.MEDIUM);
            boolean enabled = s.getBoolean("enabled", true);
            double threshold = s.getDouble("threshold", -1);
            int cooldown = s.getInt("cooldown-ms", 1000);
            List<String> actions = s.getStringList("actions");
            if (actions.isEmpty()) {
                actions = List.of("LOG");
            }
            Rule rule = Rule.builder(id)
                    .description(s.getString("description", ""))
                    .severity(severity)
                    .enabled(enabled)
                    .threshold(threshold)
                    .cooldownMs(cooldown)
                    .actions(actions)
                    .build();
            rules.put(id.toLowerCase(Locale.ROOT), rule);
        }
        plugin.getLogger().info("Loaded " + rules.size() + " rules from rules.yml");
    }

    /** Returns the configured rule or a sensible default when absent. */
    public Rule rule(String id) {
        Rule r = rules.get(id.toLowerCase(Locale.ROOT));
        if (r != null) {
            return r;
        }
        return Rule.builder(id).build();
    }

    public boolean isEnabled(String id) {
        return rule(id).enabled();
    }

    public int ruleCount() {
        return rules.size();
    }

    public Map<String, Rule> all() {
        return Map.copyOf(rules);
    }

    /** Replaces the in-memory rule set (used by tests). */
    public void put(Rule rule) {
        rules.put(rule.id().toLowerCase(Locale.ROOT), rule);
    }

    public void logInvalid(String ruleId, String reason) {
        plugin.getLogger().log(Level.WARNING, "Invalid rule '" + ruleId + "': " + reason);
    }
}
