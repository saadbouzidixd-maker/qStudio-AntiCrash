package studio.q.anticrash.exemptions;

import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Exemptions from protection: by permission, by player UUID and by world.
 * Backed by config.yml; cheap lookups suitable for the packet hot path.
 */
public final class ExemptionService {
    private volatile boolean bypassPermissionEnabled = true;
    private final Set<UUID> players = new HashSet<>();
    private final Set<String> worlds = new HashSet<>();

    public void load(org.bukkit.configuration.file.FileConfiguration config) {
        bypassPermissionEnabled = config.getBoolean("exemptions.use-bypass-permission", true);
        players.clear();
        for (String s : config.getStringList("exemptions.players")) {
            try {
                players.add(UUID.fromString(s.trim()));
            } catch (IllegalArgumentException ignored) {
                // Not a UUID; ignore silently (documented: use UUIDs).
            }
        }
        worlds.clear();
        worlds.addAll(config.getStringList("exemptions.worlds"));
    }

    /** True when the player is fully exempt from all protection. */
    public boolean isExempt(Player player) {
        if (player == null) {
            return true;
        }
        if (bypassPermissionEnabled && player.hasPermission("qanticrash.bypass")) {
            return true;
        }
        return players.contains(player.getUniqueId());
    }

    public boolean isWorldExempt(String worldName) {
        return worldName != null && worlds.contains(worldName);
    }

    public boolean isExempt(org.bukkit.entity.Entity entity) {
        if (entity == null) {
            return true;
        }
        return isWorldExempt(entity.getWorld().getName());
    }

    public int exemptPlayerCount() {
        return players.size();
    }

    public int exemptWorldCount() {
        return worlds.size();
    }
}
