package studio.q.anticrash.alerts;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import studio.q.anticrash.api.Detection;
import studio.q.anticrash.config.ConfigManager;
import studio.q.anticrash.mitigation.ActionPlan;

import java.util.List;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Real-time alerts. Notification bodies are throttled per (rule, player) to
 * prevent alert floods; messages are sent on the main thread because sending
 * Adventure components to players is a Bukkit main-thread operation.
 */
public final class AlertService {
    private final JavaPlugin plugin;
    private final ConfigManager config;
    private final CopyOnWriteArraySet<CommandSender> subscribers = new CopyOnWriteArraySet<>();
    private final java.util.concurrent.ConcurrentHashMap<Long, Long> lastSent = new java.util.concurrent.ConcurrentHashMap<>();

    public AlertService(JavaPlugin plugin, ConfigManager config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void subscribe(CommandSender sender) {
        subscribers.add(sender);
    }

    public void unsubscribe(CommandSender sender) {
        subscribers.remove(sender);
    }

    public int subscriberCount() {
        return subscribers.size();
    }

    public boolean isSubscribed(CommandSender sender) {
        return subscribers.contains(sender);
    }

    /**
     * Sends an alert for a detection if throttling permits. May be called from
     * any thread; the actual messaging is scheduled onto the main thread.
     */
    public void notifyAlert(Detection d, ActionPlan plan, long violationLevel) {
        boolean consoleEnabled = config.getBool("alerts.console", true);
        boolean ingameEnabled = config.getBool("alerts.ingame", true);
        int throttleMs = config.getInt("alerts.throttle-ms", 2000);
        if (!consoleEnabled && !ingameEnabled) {
            return;
        }
        long key = throttleKey(d);
        long now = System.currentTimeMillis();
        Long last = lastSent.get(key);
        if (last != null && now - last < throttleMs) {
            return;
        }
        lastSent.put(key, now);
        if (lastSent.size() > 512) {
            lastSent.entrySet().removeIf(e -> now - e.getValue() > 60_000);
        }

        String body = config.msg("alerts.format",
                "&8[&bqStudio&f AntiCrash&8]\n&ePlayer: &f{player}\n&eModule: &f{module}\n&eDetail: &f{detail}\n&eActual: &f{actual}\n&eLimit: &f{limit}\n&eViolations: &f{violations}\n&eAction: &f{actions}")
                .replace("{player}", d.playerName())
                .replace("{module}", d.module())
                .replace("{rule}", d.ruleId())
                .replace("{detail}", d.detail())
                .replace("{actual}", d.actual())
                .replace("{limit}", d.limit())
                .replace("{violations}", String.valueOf(violationLevel))
                .replace("{actions}", plan.actions().toString());
        Component c = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand().deserialize(body);
        sendOnMainThread(c);
    }

    private long throttleKey(Detection d) {
        int h = (d.module() + "|" + d.ruleId()).hashCode();
        long p = d.playerId() == null ? 0L : d.playerId().getMostSignificantBits();
        return (h << 32) | (p & 0xFFFFFFFFL);
    }

    private void sendOnMainThread(Component c) {
        Runnable task = () -> {
            if (config.getBool("alerts.console", true)) {
                plugin.getServer().getConsoleSender().sendMessage(c);
            }
            if (config.getBool("alerts.ingame", true)) {
                for (Player p : plugin.getServer().getOnlinePlayers()) {
                    if (subscribers.contains(p) && p.hasPermission("qanticrash.alerts")) {
                        p.sendMessage(c);
                    }
                }
            }
        };
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            plugin.getServer().getScheduler().runTask(plugin, task);
        }
    }

    /** Direct message helper used by commands (main thread only). */
    public void sendTo(CommandSender target, String msgKey, java.util.Map<String, String> placeholders) {
        String raw = config.msg(msgKey, "");
        if (raw.isEmpty()) {
            return;
        }
        for (java.util.Map.Entry<String, String> e : placeholders.entrySet()) {
            raw = raw.replace(e.getKey(), e.getValue());
        }
        Component c = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand().deserialize(raw);
        target.sendMessage(c);
    }

    public void sendRaw(CommandSender target, String legacyText) {
        Component c = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand().deserialize(legacyText);
        target.sendMessage(c);
    }

    public void sendList(CommandSender target, String msgKey, List<String> fallbackLines, java.util.Map<String, String> placeholders) {
        java.util.List<String> lines = config.messages().getStringList(msgKey);
        if (lines == null || lines.isEmpty()) {
            lines = fallbackLines;
        }
        for (String line : lines) {
            String resolved = line;
            for (java.util.Map.Entry<String, String> e : placeholders.entrySet()) {
                resolved = resolved.replace(e.getKey(), e.getValue());
            }
            sendRaw(target, resolved);
        }
    }

    public void close() {
        subscribers.clear();
    }

    /** Utility for modules to run something on the main thread. */
    public void runOnMainThread(Consumer<org.bukkit.Server> task) {
        Runnable r = () -> task.accept(plugin.getServer());
        if (Bukkit.isPrimaryThread()) {
            r.run();
        } else {
            plugin.getServer().getScheduler().runTask(plugin, r);
        }
    }
}
