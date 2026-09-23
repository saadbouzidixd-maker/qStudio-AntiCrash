package studio.q.anticrash.integrations;

import org.bukkit.Bukkit;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tracks optional integrations and their availability. The plugin never
 * requires any of these to boot; missing ones simply disable their feature
 * and are reported via /qanticrash inspect and a startup warning.
 */
public final class IntegrationRegistry {
    public record Integration(String name, String purpose, boolean present) {
    }

    private final Map<String, Integration> integrations = new LinkedHashMap<>();

    public void detect() {
        register("packetevents", "Primary packet layer (rate limits, malformed data, payload size)",
                Bukkit.getPluginManager().getPlugin("packetevents") != null);
        register("ProtocolLib", "Fallback packet layer (rate limits only)", Bukkit.getPluginManager()
                .getPlugin("ProtocolLib") != null);
        register("PlaceholderAPI", "Placeholders in alerts/messages (reserved; not required)",
                Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null);
        register("LuckPerms", "Permission context for exemptions (via standard Bukkit permissions)",
                Bukkit.getPluginManager().getPlugin("LuckPerms") != null);
    }

    private void register(String name, String purpose, boolean present) {
        integrations.put(name.toLowerCase(java.util.Locale.ROOT), new Integration(name, purpose, present));
    }

    public Map<String, Integration> all() {
        return Map.copyOf(integrations);
    }

    public boolean isPresent(String name) {
        var i = integrations.get(name.toLowerCase(java.util.Locale.ROOT));
        return i != null && i.present();
    }

    public int presentCount() {
        return (int) integrations.values().stream().filter(Integration::present).count();
    }
}
