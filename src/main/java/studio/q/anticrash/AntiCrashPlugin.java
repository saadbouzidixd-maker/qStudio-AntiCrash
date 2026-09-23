package studio.q.anticrash;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import studio.q.anticrash.alerts.AlertService;
import studio.q.anticrash.commands.AntiCrashCommand;
import studio.q.anticrash.config.ConfigManager;
import studio.q.anticrash.config.RuleEngine;
import studio.q.anticrash.core.MainThread;
import studio.q.anticrash.core.ModuleRegistry;
import studio.q.anticrash.core.ModuleToggles;
import studio.q.anticrash.core.ProtectionEngine;
import studio.q.anticrash.core.SafeMode;
import studio.q.anticrash.core.ratelimit.RateLimiter;
import studio.q.anticrash.exemptions.ExemptionService;
import studio.q.anticrash.integrations.IntegrationRegistry;
import studio.q.anticrash.logs.IncidentLogger;
import studio.q.anticrash.movement.MovementTracker;
import studio.q.anticrash.monitoring.FreezeDetector;
import studio.q.anticrash.monitoring.HealthMonitor;
import studio.q.anticrash.monitoring.MemoryGuard;
import studio.q.anticrash.packets.PacketEventsListener;
import studio.q.anticrash.packets.PacketGuard;
import studio.q.anticrash.packets.ProtocolLibListener;
import studio.q.anticrash.protection.BookSignListener;
import studio.q.anticrash.protection.ChunkListener;
import studio.q.anticrash.protection.ChatCommandListener;
import studio.q.anticrash.protection.ConnectionListener;
import studio.q.anticrash.protection.EntityListener;
import studio.q.anticrash.protection.MovementListener;
import studio.q.anticrash.protection.RedstoneListener;
import studio.q.anticrash.violations.ViolationManager;

import java.nio.file.Path;
import java.util.function.DoubleSupplier;

/**
 * qStudio AntiCrash - main entry point.
 * Wires config, engine, packet layer, protection modules, monitoring,
 * commands and scheduled maintenance. No heavy work on enable; the only
 * periodic tasks are one async health sample (default 3s) and one async
 * cleanup pass (default 60s).
 */
public final class AntiCrashPlugin extends JavaPlugin {
    private ConfigManager config;
    private RuleEngine rules;
    private ExemptionService exemptions;
    private ViolationManager violations;
    private AlertService alerts;
    private IncidentLogger incidents;
    private SafeMode safeMode;
    private ModuleRegistry modules;
    private MainThread mainThread;
    private ProtectionEngine engine;
    private ModuleToggles toggles;
    private MovementTracker movementTracker;
    private PacketGuard packetGuard;
    private HealthMonitor health;
    private FreezeDetector freezeDetector;
    private MemoryGuard memoryGuard;
    private IntegrationRegistry integrations;

    private PacketEventsListener packetEventsListener;
    private ProtocolLibListener protocolLibListener;

    private MovementListener movementListener;
    private BookSignListener bookSignListener;
    private ChatCommandListener chatCommandListener;
    private ChunkListener chunkListener;
    private EntityListener entityListener;
    private RedstoneListener redstoneListener;
    private ConnectionListener connectionListener;

    private AntiCrashCommand command;

    private int healthTaskId = -1;
    private int cleanupTaskId = -1;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.config = new ConfigManager(this);
        config.loadAll();

        this.mainThread = new MainThread(this);
        this.modules = new ModuleRegistry();
        this.safeMode = new SafeMode();
        this.rules = new RuleEngine(this, config);
        rules.load();

        this.exemptions = new ExemptionService();
        exemptions.load(config.config());

        this.violations = new ViolationManager(
                config.getInt("violations.decay-interval-seconds", 60) * 1000L,
                config.getInt("violations.decay-amount", 2),
                System::currentTimeMillis);
        this.alerts = new AlertService(this, config);
        this.incidents = new IncidentLogger(
                getDataFolder().toPath(),
                getServer().getVersion(),
                getDescription().getVersion());
        this.toggles = new ModuleToggles();
        toggles.reload(config);

        this.movementTracker = new MovementTracker();
        this.engine = new ProtectionEngine(config, rules, exemptions, violations, alerts,
                incidents, safeMode, modules, mainThread);

        this.packetGuard = new PacketGuard();
        this.health = new HealthMonitor(this, tpsSupplier());
        this.freezeDetector = new FreezeDetector(this, engine, alerts, health, safeMode, mainThread);
        this.memoryGuard = new MemoryGuard(engine, alerts, safeMode, mainThread);
        this.integrations = new IntegrationRegistry();
        integrations.detect();

        registerModules();
        setupPacketLayer();
        registerBukkitListeners();
        registerCommand();
        applyModuleConfiguration();

        // Slow maintenance: sampling + cleanup. Both async, low cadence.
        int sampleInterval = Math.max(config.getInt("performance.sample-interval-ticks", 60), 20);
        healthTaskId = getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            health.sample();
            memoryGuard.check(health.heapPercent());
            freezeDetector.check();
            if (health.sampleCount() % 20 == 0) {
                mainThread.runDeferred(health::sampleWorlds);
            }
        }, sampleInterval, sampleInterval).getTaskId();

        int cleanupSeconds = Math.max(config.getInt("performance.cleanup-interval-seconds", 60), 10);
        cleanupTaskId = getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            long now = System.currentTimeMillis();
            packetGuard.cleanup(now);
            violations.cleanup(cleanupSeconds * 2000L);
        }, cleanupSeconds * 20L, cleanupSeconds * 20L).getTaskId();

        getLogger().info("qStudio AntiCrash enabled. Packet layer: " + describePacketLayer()
                + " | modules: " + modules.enabledCount() + "/" + modules.count());
    }

    private DoubleSupplier tpsSupplier() {
        // Bukkit.getTPS() is a Paper/Spigot API present on all modern servers;
        // read the 1-minute average. Guarded so unit tests / exotic builds fail soft.
        return () -> {
            try {
                double[] tps = Bukkit.getTPS();
                return tps != null && tps.length > 0 ? tps[0] : 20.0;
            } catch (Throwable t) {
                return 20.0;
            }
        };
    }

    private void registerModules() {
        modules.register("PacketRate", true, "Per-category and global packet rate limiting", "packet");
        modules.register("Movement", toggles.movement, "Movement packet/event validation", "mixed");
        modules.register("BookGuard", toggles.books, "Book page/size/title limits", "bukkit");
        modules.register("SignGuard", toggles.signs, "Sign line length limits", "bukkit");
        modules.register("ChatGuard", toggles.chat, "Chat/command/click rate and size limits", "bukkit");
        modules.register("ChunkGuard", toggles.chunks, "Chunk load/generation storm monitoring", "bukkit");
        modules.register("EntityGuard", toggles.entities, "Spawn rate, projectile spam, chunk caps", "bukkit");
        modules.register("RedstoneGuard", toggles.redstone, "Redstone/physics/piston storm suppression", "bukkit");
        modules.register("HopperGuard", toggles.hopperFlow, "Hopper item-move storm suppression", "bukkit");
        modules.register("ConnectionGuard", toggles.connections, "Login burst and reconnect-spam control", "bukkit");
        modules.register("MemoryGuard", toggles.memory, "Heap pressure monitoring", "async");
        modules.register("FreezeDetector", true, "TPS/MSPT degradation detection", "async");
    }

    private void setupPacketLayer() {
        boolean wantPackets = toggles.packets;
        boolean peAvailable = getServer().getPluginManager().getPlugin("packetevents") != null;
        boolean plAvailable = getServer().getPluginManager().getPlugin("ProtocolLib") != null;

        if (peAvailable) {
            try {
                PacketEventsListener.TextLimits limits = new PacketEventsListener.TextLimits() {
                    @Override
                    public int maxCommandLength() {
                        return config.getInt("chat.max-command-length", 256);
                    }

                    @Override
                    public int maxChatLength() {
                        return config.getInt("chat.max-message-length", 256);
                    }

                    @Override
                    public int maxTabLength() {
                        return config.getInt("chat.max-tab-length", 256);
                    }

                    @Override
                    public int maxPayloadBytes() {
                        return config.getInt("packets.max-payload-bytes", 32767);
                    }

                    @Override
                    public boolean isChannelDenied(String channel) {
                        for (String denied : config.getStringList("packets.denied-channels")) {
                            if (denied.equalsIgnoreCase(channel)) {
                                return true;
                            }
                        }
                        return false;
                    }
                };
                packetEventsListener = new PacketEventsListener(engine, packetGuard, movementTracker,
                        mainThread, limits);
                com.github.retrooper.packetevents.PacketEvents.getAPI().getEventManager()
                        .registerListeners(packetEventsListener);
                modules.register("PacketRate", true, "Per-category and global packet rate limiting (PacketEvents)",
                        "packet");
            } catch (Throwable t) {
                getLogger().warning("PacketEvents detected but registration failed: " + t.getMessage());
                packetEventsListener = null;
            }
        } else if (plAvailable) {
            try {
                protocolLibListener = new ProtocolLibListener(this, engine, packetGuard, mainThread);
                boolean ok = protocolLibListener.register();
                if (ok) {
                    modules.register("PacketRate", true,
                            "Per-category and global packet rate limiting (ProtocolLib; size checks unavailable)",
                            "packet");
                }
            } catch (Throwable t) {
                getLogger().warning("ProtocolLib detected but registration failed: " + t.getMessage());
                protocolLibListener = null;
            }
        } else {
            getLogger().warning(
                    "Neither PacketEvents nor ProtocolLib found - packet-level protection is DISABLED. "
                            + "Bukkit-layer modules remain active. Install PacketEvents 2.x for full protection.");
        }
    }

    private void registerBukkitListeners() {
        movementListener = new MovementListener(engine, toggles, movementTracker, mainThread);
        bookSignListener = new BookSignListener(engine, toggles, new BookSignListener.Limits() {
            @Override
            public int maxBookPages() {
                return config.getInt("books.max-pages", 100);
            }

            @Override
            public int maxBookPageLength() {
                return config.getInt("books.max-page-length", 5000);
            }

            @Override
            public long maxBookTotalLength() {
                return config.getInt("books.max-total-length", 50000);
            }

            @Override
            public int maxBookTitleLength() {
                return config.getInt("books.max-title-length", 32);
            }

            @Override
            public int maxSignLineLength() {
                return config.getInt("signs.max-line-length", 128);
            }
        });
        chatCommandListener = new ChatCommandListener(engine, toggles);
        chunkListener = new ChunkListener(engine, toggles);
        entityListener = new EntityListener(engine, toggles, exemptions,
                config.getInt("entities.max-spawns-per-second", 100),
                config.getInt("entities.max-projectiles-per-second", 20),
                config.getInt("entities.max-interactions-per-second", 40));
        redstoneListener = new RedstoneListener(engine, toggles);
        connectionListener = new ConnectionListener(engine, toggles, movementTracker, chunkListener,
                config.getInt("connections.max-logins-per-ip-per-window", 8),
                config.getInt("connections.window-seconds", 10) * 1000);

        getServer().getPluginManager().registerEvents(movementListener, this);
        getServer().getPluginManager().registerEvents(bookSignListener, this);
        getServer().getPluginManager().registerEvents(chatCommandListener, this);
        getServer().getPluginManager().registerEvents(chunkListener, this);
        getServer().getPluginManager().registerEvents(entityListener, this);
        getServer().getPluginManager().registerEvents(redstoneListener, this);
        getServer().getPluginManager().registerEvents(connectionListener, this);
    }

    private void registerCommand() {
        PluginCommand cmd = getCommand("qanticrash");
        if (cmd != null) {
            command = new AntiCrashCommand(this);
            cmd.setExecutor(command);
            cmd.setTabCompleter(command);
        }
    }

    private void applyModuleConfiguration() {
        java.util.Map<String, Integer> perSecond = config.getIntMap("packets.limits.max-per-second");
        java.util.Map<String, Integer> bursts = config.getIntMap("packets.limits.burst");
        int global = config.getInt("packets.limits.global-per-second", 1000);
        packetGuard.configure(perSecond, bursts, global);

        chatCommandListener.configure(
                config.getInt("chat.max-message-length", 256),
                config.getInt("chat.max-command-length", 256),
                config.getInt("chat.max-per-second", 10),
                config.getInt("commands.max-per-second", 20),
                config.getInt("items.max-clicks-per-second", 80));
        chunkListener.configure(
                config.getInt("chunks.max-new-per-player-per-window", 80),
                config.getInt("chunks.max-new-global-per-window", 600));
        entityListener.configure(
                config.getInt("entities.max-per-chunk", 200),
                config.getBool("entities.chunk-cap-enabled", true));
        redstoneListener.configure(
                config.getInt("redstone.max-changes-per-second", 4000),
                config.getInt("redstone.max-physics-per-second", 8000),
                config.getInt("redstone.max-pistons-per-second", 400),
                config.getInt("redstone.max-hopper-moves-per-second", 2000),
                config.getInt("redstone.max-dispenses-per-second", 200),
                config.getInt("redstone.storm-cooldown-ms", 5000));
        connectionListener.configure(
                config.getInt("connections.max-logins-per-ip-per-window", 8),
                config.getInt("connections.window-seconds", 10) * 1000,
                true);
    }

    /** Re-applies config + rules. Used by /qanticrash reload. */
    public void reloadAll() {
        config.reload();
        rules.load();
        exemptions.load(config.config());
        toggles.reload(config);
        applyModuleConfiguration();
        packetGuard.setEnabled(toggles.packets);
    }

    public String describePacketLayer() {
        if (packetEventsListener != null) {
            return "PacketEvents";
        }
        if (protocolLibListener != null) {
            return "ProtocolLib";
        }
        return "none";
    }

    public String minecraftVersion() {
        return getServer().getBukkitVersion();
    }

    @Override
    public void onDisable() {
        if (healthTaskId != -1) {
            getServer().getScheduler().cancelTask(healthTaskId);
        }
        if (cleanupTaskId != -1) {
            getServer().getScheduler().cancelTask(cleanupTaskId);
        }
        if (protocolLibListener != null) {
            protocolLibListener.unregister();
        }
        if (incidents != null) {
            incidents.close();
        }
        if (alerts != null) {
            alerts.close();
        }
        getLogger().info("qStudio AntiCrash disabled.");
    }

    // ---- accessors for commands ----
    public ConfigManager configs() {
        return config;
    }

    public RuleEngine rules() {
        return rules;
    }

    public ProtectionEngine engine() {
        return engine;
    }

    public PacketGuard packetGuard() {
        return packetGuard;
    }

    public HealthMonitor health() {
        return health;
    }

    public FreezeDetector freezeDetector() {
        return freezeDetector;
    }

    public MemoryGuard memoryGuard() {
        return memoryGuard;
    }

    public ModuleToggles toggles() {
        return toggles;
    }

    public IntegrationRegistry integrations() {
        return integrations;
    }

    public IncidentLogger incidents() {
        return incidents;
    }

    public MovementTracker movementTracker() {
        return movementTracker;
    }

    public ChunkListener chunkListener() {
        return chunkListener;
    }
}
