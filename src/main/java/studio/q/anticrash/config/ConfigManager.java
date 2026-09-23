package studio.q.anticrash.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * Loads and owns the three configuration files (config.yml, rules.yml,
 * messages.yml). Falls back to bundled defaults for any missing key so a
 * partial user config never breaks the plugin.
 */
public final class ConfigManager {
    private final JavaPlugin plugin;
    private FileConfiguration config;
    private FileConfiguration rules;
    private FileConfiguration messages;

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadAll() {
        ensureFile("config.yml");
        ensureFile("rules.yml");
        ensureFile("messages.yml");
        config = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "config.yml"));
        rules = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "rules.yml"));
        messages = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "messages.yml"));
        applyDefaults(config, "config.yml");
        applyDefaults(rules, "rules.yml");
        applyDefaults(messages, "messages.yml");
    }

    public void reload() {
        loadAll();
    }

    /** Saves the bundled resource if the file does not exist, then merges missing keys. */
    private void ensureFile(String name) {
        File file = new File(plugin.getDataFolder(), name);
        if (!file.exists()) {
            plugin.saveResource(name, false);
        }
    }

    private void applyDefaults(FileConfiguration target, String resourceName) {
        try (InputStream in = plugin.getResource(resourceName)) {
            if (in == null) {
                return;
            }
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new java.io.InputStreamReader(new java.io.ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)),
                            StandardCharsets.UTF_8));
            target.setDefaults(defaults);
            target.options().copyDefaults(true);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not read bundled defaults for " + resourceName, ex);
        }
    }

    public void saveMessages() {
        try {
            messages.save(new File(plugin.getDataFolder(), "messages.yml"));
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not save messages.yml", ex);
        }
    }

    public FileConfiguration config() {
        return config;
    }

    public FileConfiguration rules() {
        return rules;
    }

    public FileConfiguration messages() {
        return messages;
    }

    // ---- typed accessors with defaults ----

    public boolean getBool(String path, boolean def) {
        return config.getBoolean(path, def);
    }

    public int getInt(String path, int def) {
        return config.getInt(path, def);
    }

    public double getDouble(String path, double def) {
        return config.getDouble(path, def);
    }

    public String getString(String path, String def) {
        return config.getString(path, def);
    }

    public List<String> getStringList(String path) {
        return config.getStringList(path);
    }

    /** Reads a nested map of string -> int (used for per-category packet limits). */
    public Map<String, Integer> getIntMap(String path) {
        Map<String, Integer> out = new HashMap<>();
        ConfigurationSection section = config.getConfigurationSection(path);
        if (section == null) {
            return out;
        }
        Set<String> keys = section.getKeys(false);
        for (String k : keys) {
            out.put(k.toLowerCase(Locale.ROOT), section.getInt(k));
        }
        return out;
    }

    /** Message lookup with graceful fallback. */
    public String msg(String key, String fallback) {
        String value = messages.getString(key);
        return value == null ? fallback : value;
    }
}
