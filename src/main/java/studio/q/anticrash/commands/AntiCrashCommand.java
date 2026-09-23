package studio.q.anticrash.commands;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import studio.q.anticrash.AntiCrashPlugin;
import studio.q.anticrash.api.Detection;
import studio.q.anticrash.api.Severity;
import studio.q.anticrash.mitigation.ActionPlan;
import studio.q.anticrash.monitoring.HealthMonitor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * /qanticrash root command. All output goes through AlertService formatting
 * (legacy-&amp; codes). Subcommands match plugin.yml exactly.
 */
public final class AntiCrashCommand implements CommandExecutor, TabCompleter {
    private static final List<String> SUBS = List.of(
            "reload", "status", "debug", "violations", "reset", "alerts",
            "modules", "inspect", "test", "safemode", "version");

    private final AntiCrashPlugin plugin;

    public AntiCrashCommand(AntiCrashPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("qanticrash.admin")) {
            plugin.engine().alerts().sendRaw(sender, "&cYou do not have permission to use qStudio AntiCrash.");
            return true;
        }
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> handleReload(sender);
            case "status" -> handleStatus(sender);
            case "debug" -> handleDebug(sender, args);
            case "violations" -> handleViolations(sender, args);
            case "reset" -> handleReset(sender, args);
            case "alerts" -> handleAlerts(sender);
            case "modules" -> handleModules(sender);
            case "inspect" -> handleInspect(sender);
            case "test" -> handleTest(sender);
            case "safemode" -> handleSafeMode(sender);
            case "version" -> handleVersion(sender);
            default -> sendHelp(sender);
        }
        return true;
    }

    private void sendHelp(CommandSender sender) {
        plugin.engine().alerts().sendList(sender, "commands.help", List.of(
                "&bqStudio AntiCrash &7- by qSa3ed",
                "&e/qanticrash reload &7- reload configuration and rules",
                "&e/qanticrash status &7- protection and server status",
                "&e/qanticrash debug player <player> &7- why modules flag a player",
                "&e/qanticrash violations <player> &7- violation levels",
                "&e/qanticrash reset <player> &7- reset a player's violations",
                "&e/qanticrash alerts &7- toggle real-time alerts",
                "&e/qanticrash modules &7- list protection modules",
                "&e/qanticrash inspect &7- full server diagnostics",
                "&e/qanticrash test &7- self-test of protection components",
                "&e/qanticrash safemode &7- toggle Safe Mode",
                "&e/qanticrash version &7- version info"), Map.of());
    }

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("qanticrash.reload")) {
            noPerm(sender);
            return;
        }
        try {
            plugin.reloadAll();
            plugin.engine().alerts().sendRaw(sender, "&aqStudio AntiCrash configuration reloaded.");
        } catch (Exception ex) {
            plugin.engine().alerts().sendRaw(sender, "&cReload failed: " + ex.getMessage());
        }
    }

    private void handleStatus(CommandSender sender) {
        HealthMonitor h = plugin.health();
        boolean pe = plugin.describePacketLayer().equals("PacketEvents");
        boolean pl = plugin.describePacketLayer().equals("ProtocolLib");
        plugin.engine().alerts().sendList(sender, "commands.status", List.of(
                "&bqStudio AntiCrash &7v" + plugin.getDescription().getVersion(),
                "&eProtection: &f" + (plugin.packetGuard().enabled() ? "active" : "packets off")
                        + " &7| layer: &f" + plugin.describePacketLayer(),
                "&eSafe Mode: &f" + (plugin.engine().safeMode().isActive() ? "&cON" : "&aoff"),
                "&eTPS: &f" + fmt(h.tps()) + " &7| MSPT: &f" + fmt(h.mspt()),
                "&eHeap: &f" + fmt(h.heapPercent()) + "% &7| Players: &f" + h.players(),
                "&eEntities: &f" + h.entities() + " &7| Chunks: &f" + h.chunks(),
                "&eModules: &f" + plugin.engine().modules().enabledCount() + "/"
                        + plugin.engine().modules().count()
                        + " &7| Rules: &f" + plugin.rules().ruleCount(),
                "&eIncidents: &f" + plugin.engine().incidentCount()
                        + " &7| Tracked players: &f" + plugin.engine().violations().trackedPlayers()),
                Map.of());
    }

    private void handleDebug(CommandSender sender, String[] args) {
        if (!sender.hasPermission("qanticrash.debug")) {
            noPerm(sender);
            return;
        }
        if (args.length < 3 || !args[1].equalsIgnoreCase("player")) {
            plugin.engine().alerts().sendRaw(sender, "&eUsage: /qanticrash debug player <player>");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[2]);
        if (target == null) {
            plugin.engine().alerts().sendRaw(sender, "&cPlayer not found: " + args[2]);
            return;
        }
        UUID pid = target.getUniqueId();
        Map<String, Long> snapshot = plugin.engine().snapshot(pid);
        List<String> lines = new ArrayList<>();
        lines.add("&bDebug: &f" + target.getName());
        lines.add("&eExempt: &f" + plugin.engine().exemptions().isExempt(target));
        lines.add("&eTotal violation level: &f" + plugin.engine().violations().totalLevel(pid));
        if (snapshot.isEmpty()) {
            lines.add("&7No active violation records.");
        } else {
            for (Map.Entry<String, Long> e : snapshot.entrySet()) {
                lines.add("&e" + e.getKey() + ": &flevel " + e.getValue());
            }
        }
        lines.add("&eMovement tracked: &f" + (plugin.movementTracker().tracked() > 0));
        lines.add("&7Explanation: modules raise a level each time a rule fires; decay runs every "
                + plugin.configs().getInt("violations.decay-interval-seconds", 60) + "s.");
        plugin.engine().alerts().sendList(sender, "commands.debug", lines, Map.of());
    }

    private void handleViolations(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.engine().alerts().sendRaw(sender, "&eUsage: /qanticrash violations <player>");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            plugin.engine().alerts().sendRaw(sender, "&cPlayer not found: " + args[1]);
            return;
        }
        UUID pid = target.getUniqueId();
        Map<String, Long> snapshot = plugin.engine().snapshot(pid);
        List<String> lines = new ArrayList<>();
        lines.add("&bViolations for &f" + target.getName());
        if (snapshot.isEmpty()) {
            lines.add("&aClean - no violations recorded.");
        } else {
            for (Map.Entry<String, Long> e : snapshot.entrySet()) {
                lines.add("&e" + e.getKey() + ": &f" + e.getValue());
            }
        }
        plugin.engine().alerts().sendList(sender, "commands.violations", lines, Map.of());
    }

    private void handleReset(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.engine().alerts().sendRaw(sender, "&eUsage: /qanticrash reset <player>");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            // Also try offline reset by exact UUID match via tracked players.
            plugin.engine().alerts().sendRaw(sender, "&cPlayer not found (must be online): " + args[1]);
            return;
        }
        plugin.engine().violations().reset(target.getUniqueId());
        plugin.engine().alerts().sendRaw(sender, "&aViolations reset for " + target.getName() + ".");
    }

    private void handleAlerts(CommandSender sender) {
        if (!sender.hasPermission("qanticrash.alerts")) {
            noPerm(sender);
            return;
        }
        if (plugin.engine().alerts().isSubscribed(sender)) {
            plugin.engine().alerts().unsubscribe(sender);
            plugin.engine().alerts().sendRaw(sender, "&cAlerts disabled for you.");
        } else {
            plugin.engine().alerts().subscribe(sender);
            plugin.engine().alerts().sendRaw(sender, "&aAlerts enabled for you.");
        }
    }

    private void handleModules(CommandSender sender) {
        List<String> lines = new ArrayList<>();
        lines.add("&bqStudio AntiCrash modules:");
        for (var e : plugin.engine().modules().snapshot()) {
            String color = e.enabled() ? "&a" : "&c";
            lines.add("&e" + e.name() + " " + color + (e.enabled() ? "ON" : "OFF")
                    + " &7[" + e.layer() + "] " + e.description());
        }
        plugin.engine().alerts().sendList(sender, "commands.modules", lines, Map.of());
    }

    private void handleInspect(CommandSender sender) {
        if (!sender.hasPermission("qanticrash.inspect")) {
            noPerm(sender);
            return;
        }
        HealthMonitor h = plugin.health();
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        List<String> lines = new ArrayList<>();
        lines.add("&bqStudio AntiCrash &7- diagnostics");
        lines.add("&eVersion: &f" + plugin.getDescription().getVersion()
                + " &7| Minecraft: &f" + plugin.minecraftVersion());
        lines.add("&eServer: &f" + plugin.getServer().getName() + " " + plugin.getServer().getVersion());
        lines.add("&eJava: &f" + System.getProperty("java.version"));
        lines.add("&ePacket Layer: &f" + plugin.describePacketLayer());
        lines.add("&ePacketEvents: &f" + (Bukkit.getPluginManager().getPlugin("packetevents") != null)
                + " &7| ProtocolLib: &f" + (Bukkit.getPluginManager().getPlugin("ProtocolLib") != null));
        lines.add("&eEnabled Modules: &f" + plugin.engine().modules().enabledCount()
                + "/" + plugin.engine().modules().count());
        lines.add("&eProtection Status: &f" + (plugin.packetGuard().enabled() ? "active" : "packets disabled"));
        lines.add("&eTPS: &f" + fmt(h.tps()) + " &7| MSPT: &f" + fmt(h.mspt()));
        lines.add("&eMemory: &f" + (used / 1048576) + "MB used / " + (rt.maxMemory() / 1048576) + "MB max ("
                + fmt(h.heapPercent()) + "%)");
        lines.add("&eLoaded Chunks: &f" + h.chunks() + " &7| Entities: &f" + h.entities()
                + " &7| Players: &f" + h.players());
        lines.add("&eSafe Mode: &f" + (plugin.engine().safeMode().isActive()
                ? "&cON (" + plugin.engine().safeMode().reason() + ")" : "&aoff"));
        lines.add("&ePackets seen: &f" + plugin.packetGuard().totalPackets()
                + " &7| blocked: &f" + plugin.packetGuard().blockedPackets()
                + " &7| active buckets: &f" + plugin.packetGuard().activeBuckets());
        plugin.engine().alerts().sendList(sender, "commands.inspect", lines, Map.of());
    }

    private void handleTest(CommandSender sender) {
        List<String> lines = new ArrayList<>();
        lines.add("&bSelf-test:");
        // Rate limiter live probe (no server state touched).
        studio.q.anticrash.core.ratelimit.RateLimiter rl =
                new studio.q.anticrash.core.ratelimit.RateLimiter(2, 2, 1000);
        long key = 12345L;
        long now = System.currentTimeMillis();
        boolean firstTwo = rl.tryConsume(key, now) && rl.tryConsume(key, now);
        boolean third = rl.tryConsume(key, now);
        lines.add("&eRateLimiter: &f" + pass(firstTwo && !third));
        // Movement checks probe.
        boolean nanRejected = !studio.q.anticrash.movement.MovementChecks.isFinite(Double.NaN, 0, 0);
        boolean rotOk = studio.q.anticrash.movement.MovementChecks.isFiniteRotation(0f, 90f);
        lines.add("&eMovementChecks: &f" + pass(nanRejected && rotOk));
        // Rule engine probe.
        boolean ruleLoaded = plugin.rules().isEnabled("packet-flood");
        lines.add("&eRuleEngine: &f" + pass(ruleLoaded) + " &7(" + plugin.rules().ruleCount() + " rules)");
        // Violation manager probe.
        studio.q.anticrash.violations.ViolationManager vm =
                new studio.q.anticrash.violations.ViolationManager(60_000, 2, System::currentTimeMillis);
        UUID probe = new UUID(0L, 999L);
        vm.add(probe, "test", 5);
        boolean vOk = vm.level(probe, "test") == 5;
        vm.reset(probe);
        lines.add("&eViolationManager: &f" + pass(vOk));
        // Packet layer probe.
        lines.add("&ePacket layer: &f" + plugin.describePacketLayer());
        // Health sample probe.
        plugin.health().sample();
        lines.add("&eHealth sampling: &f" + pass(plugin.health().tps() > 0));
        plugin.engine().alerts().sendList(sender, "commands.test", lines, Map.of());
    }

    private String pass(boolean ok) {
        return ok ? "&aOK" : "&cFAIL";
    }

    private void handleSafeMode(CommandSender sender) {
        boolean now = !plugin.engine().safeMode().isActive();
        String reason = now ? "manual" : "";
        plugin.engine().safeMode().setActive(now, reason);
        if (now) {
            plugin.engine().alerts().sendRaw(sender, "&cSafe Mode ACTIVATED - protections are stricter. "
                    + "Use /qanticrash safemode again to disable.");
            Detection d = Detection.builder(null, "server", "SafeMode", "safemode-manual")
                    .detail("Safe Mode enabled manually by " + sender.getName())
                    .actual("manual toggle")
                    .limit("-")
                    .severity(Severity.MEDIUM)
                    .build();
            ActionPlan plan = plugin.engine().handle(d);
            if (plan.shouldLog()) {
                plugin.getLogger().info("Safe Mode enabled by " + sender.getName());
            }
        } else {
            plugin.engine().alerts().sendRaw(sender, "&aSafe Mode deactivated - normal protection levels.");
        }
    }

    private void handleVersion(CommandSender sender) {
        plugin.engine().alerts().sendList(sender, "commands.version", List.of(
                "&bqStudio AntiCrash &7v" + plugin.getDescription().getVersion(),
                "&7Author: &fqSa3ed &7| Brand: &bqStudio",
                "&7Minecraft: &f" + plugin.minecraftVersion(),
                "&7Server: &f" + plugin.getServer().getName() + " " + plugin.getServer().getVersion(),
                "&7Java: &f" + System.getProperty("java.version"),
                "&7Packet layer: &f" + plugin.describePacketLayer(),
                "&7https://github.com/saadbouzidixd-maker/qStudio-AntiCrash"), Map.of());
    }

    private void noPerm(CommandSender sender) {
        plugin.engine().alerts().sendRaw(sender, "&cYou do not have permission for this subcommand.");
    }

    private String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("qanticrash.admin")) {
            return List.of();
        }
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (String s : SUBS) {
                if (s.startsWith(prefix)) {
                    out.add(s);
                }
            }
            return out;
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("debug") || args[0].equalsIgnoreCase("violations")
                || args[0].equalsIgnoreCase("reset"))) {
            List<String> out = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    out.add(p.getName());
                }
            }
            return out;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("debug")) {
            return List.of("player");
        }
        return List.of();
    }
}
