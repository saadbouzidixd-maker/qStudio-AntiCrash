package studio.q.anticrash.config;

import org.junit.jupiter.api.Test;

import org.bukkit.configuration.file.YamlConfiguration;

import studio.q.anticrash.api.Severity;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loads the bundled config.yml / rules.yml / messages.yml resources through
 * Bukkit's own YAML parser and verifies the keys modules depend on. This
 * runs without a server: YamlConfiguration only needs snakeyaml.
 */
class ConfigFilesTest {

    private static YamlConfiguration load(String resource) {
        try (InputStreamReader reader = new InputStreamReader(
                Objects.requireNonNull(ConfigFilesTest.class.getClassLoader().getResourceAsStream(resource)),
                StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot load " + resource, ex);
        }
    }

    @Test
    void configYamlIsWellFormedAndComplete() {
        YamlConfiguration c = load("config.yml");
        assertTrue(c.getBoolean("packets.enabled", false));
        assertTrue(c.getBoolean("movement.enabled", false));
        assertTrue(c.getBoolean("books.enabled", false));
        assertTrue(c.getBoolean("signs.enabled", false));
        assertTrue(c.getBoolean("chat.enabled", false));
        assertTrue(c.getBoolean("chunks.enabled", false));
        assertTrue(c.getBoolean("entities.enabled", false));
        assertTrue(c.getBoolean("redstone.enabled", false));
        assertTrue(c.getBoolean("memory.enabled", false));
        assertTrue(c.getBoolean("connections.enabled", false));

        assertEquals(32767, c.getInt("packets.max-payload-bytes", -1));
        assertEquals(100, c.getInt("books.max-pages", -1));
        assertEquals(128, c.getInt("signs.max-line-length", -1));
        assertEquals(8, c.getInt("connections.max-logins-per-ip-per-window", -1));
        assertEquals(12.0, c.getDouble("performance.min-tps", -1), 1e-9);
        assertTrue(c.getBoolean("safemode.auto-enable-on-degradation", false));
        assertNotNull(c.getConfigurationSection("packets.limits.max-per-second"));
        assertNotNull(c.getConfigurationSection("exemptions"));
    }

    @Test
    void rulesYamlCoversEveryRuleUsedByModules() {
        YamlConfiguration r = load("rules.yml");
        var section = r.getConfigurationSection("rules");
        assertNotNull(section, "rules section missing");
        String[] required = {
                "packet-flood", "packet-malformed", "payload-oversize",
                "chat-oversize", "command-oversize", "tab-abuse",
                "movement-invalid", "movement-extreme-delta", "movement-rotation-spam",
                "book-pages", "book-size", "book-page-size", "book-title-size", "sign-oversize",
                "chat-flood", "command-flood", "click-flood", "item-amount",
                "chunk-generation-storm", "chunk-request-storm",
                "entity-spawn-storm", "entity-chunk-cap", "projectile-spam", "interaction-flood",
                "redstone-storm", "physics-storm", "piston-storm", "hopper-storm", "dispenser-storm",
                "login-flood", "reconnect-spam",
                "memory-pressure", "memory-warning", "tps-collapse", "safemode-manual"
        };
        for (String id : required) {
            assertTrue(section.contains(id), "rules.yml missing rule: " + id);
        }
        // Every rule must have a valid severity and at least one action.
        for (String id : section.getKeys(false)) {
            String sev = section.getString(id + ".severity");
            // Severity.parse falls back on bad input; here we assert the literal value parses.
            assertTrue(sev == null || Severity.parse(sev, Severity.LOW).name().equalsIgnoreCase(sev.trim()),
                    "rule " + id + " has invalid severity: " + sev);
            var actions = section.getStringList(id + ".actions");
            assertTrue(!actions.isEmpty(), "rule " + id + " has no actions");
        }
    }

    @Test
    void messagesYamlHasAlertFormat() {
        YamlConfiguration m = load("messages.yml");
        String format = m.getString("alerts.format");
        assertNotNull(format);
        assertTrue(format.contains("{player}"));
        assertTrue(format.contains("{module}"));
        assertTrue(format.contains("{actual}"));
        assertTrue(format.contains("{limit}"));
        var help = m.getStringList("commands.help");
        assertTrue(!help.isEmpty());
    }
}
